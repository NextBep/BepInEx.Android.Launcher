package com.bepinex.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.widget.Toast
import com.bepinex.android.fusion.*
import com.bepinex.android.log.LogOverlayService
import com.bepinex.android.settings.AppSettings
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * Bootstrap activity that injects BepInEx into the target game process
 * via Pine hooks, custom libmain.so/libfusion.so, and CoreCLR bootstrap.
 */
class BootstrapActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity"
        const val EXTRA_ACTIVE_MODPACK = "active_modpack" // null/empty = vanilla

        private const val BACKUP_UNITY_VERSION = "2017.0.0"
        private const val GLOBAL_METADATA_FILE = "global-metadata.dat"

        // Unity version pattern: X.Y.Z[abcfp]N or X.Y.ZrcN
        private val UNITY_VERSION_PATTERN =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[abcfp]\\d+|rc\\d+)?$")

        // Known offsets in Unity data files where version strings appear
        private val VERSION_LOOKUP_MAP = mapOf(
            "globalgamemanagers" to intArrayOf(0x14, 0x30),
            "data.unity3d" to intArrayOf(0x12),
            "mainData" to intArrayOf(0x14)
        )
    }

    private val hookInstalled = AtomicBoolean(false)
    private val fusionInitialized = AtomicBoolean(false)
    private var preparedConfig: FusionConfig? = null
    private var targetPackage: String? = null

    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.activity_list_item)

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (targetPackage.isNullOrEmpty()) {
            failAndFinish("No target package specified!")
            return
        }

        BepInExLog.i("=== Bootstrap: $targetPackage ===")

        Thread({
            try { runBootstrapFlow(targetPackage) }
            catch (e: Exception) { failAndFinish("Bootstrap failed: ${e.message}", e) }
        }, "bootstrap-flow").start()
    }

    // Bootstrap flow

    private fun runBootstrapFlow(packageName: String) {
        this.targetPackage = packageName
        val targetPackage = packageName

        // 1. Resolve game launcher
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: throw IllegalStateException("No launch intent for $targetPackage")

        val launcher = launchIntent.component
            ?: launchIntent.resolveActivity(packageManager)
            ?: throw IllegalStateException("Cannot resolve launcher for $targetPackage")

        BepInExLog.i("Game launcher: ${launcher.className}")
        val targetOrientation = resolveTargetOrientation(launcher)

        // 2. Create game context (with DEX access)
        val gameContext: Context = try {
            createPackageContext(targetPackage,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create game context", e)
        }

        BepInExLog.i("Game context: ${gameContext.packageCodePath}")

        // 3. Prepare Fusion state (paths, extract zips, copy data, detect version)
        val useOriginalLibUnity = intent.getBooleanExtra(EXTRA_USE_ORIGINAL_LIBUNITY, true)
        preparedConfig = prepareFusionState(targetPackage, gameContext, useOriginalLibUnity)

        // 4. Install base Pine hooks
        BepInExLog.i("Installing Pine hooks...")
        try {
            ClassLoaderHooks.installHooks(gameContext.classLoader)
            PackageManagerHooks.installHooks(packageManager)
            InstrumentationHooks.install()
            UnityPlayerHooks.installHooks(gameContext)
            BepInExLog.i("Base hooks installed")
        } catch (e: Exception) {
            throw IllegalStateException("Failed to install base hooks", e)
        }

        // 5. Hook game launcher's onCreate (optional — some launchers inherit it)
        val launcherClassName = launcher.className
        installLauncherOnCreateHook(gameContext, gameContext.classLoader, launcherClassName)

        // 6. Start game launcher Activity via StubActivity
        try {
            val launcherClass = gameContext.classLoader.loadClass(launcherClassName)
            BepInExLog.i("Starting game launcher: ${launcherClass.name}")
            initializeFusion(null, null)

            runOnMainThread {
                try {
                    val intent = Intent(this, launcherClass).apply {
                        putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation)
                    }
                    val intentWrapped = Intent(this, StubActivity::class.java).apply {
                        putExtra(InstrumentationHooks.EXTRA_IS_DYNAMIC_ACTIVITY, true)
                        putExtra(InstrumentationHooks.EXTRA_ORIGINAL_INTENT, intent)
                        putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation)
                    }
                    startActivity(intentWrapped)
                    finish()
                } catch (t: Throwable) {
                    failAndFinish("Failed to start game: ${t.message}")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load launcher class: $launcherClassName", e)
        }
    }

    // Launcher onCreate hook

    private fun installLauncherOnCreateHook(
        gameContext: Context,
        gameClassLoader: ClassLoader,
        launcherClassName: String
    ): Boolean {
        if (hookInstalled.get()) return true

        return try {
            val launcherClass = Class.forName(launcherClassName, false, gameClassLoader)
            val onCreateMethod = launcherClass.declaredMethods.firstOrNull { method ->
                method.name == "onCreate" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Bundle::class.java
            } ?: throw NoSuchMethodException("onCreate(Bundle) not found in $launcherClassName")

            onCreateMethod.isAccessible = true

            Pine.hook(onCreateMethod, object : MethodHook() {
                override fun beforeCall(callFrame: Pine.CallFrame) {
                    if (callFrame.thisObject !is Activity) return

                    val activity = callFrame.thisObject as Activity

                    // Set UnityPlayer.currentActivity early
                    try {
                        val upClass = gameClassLoader.loadClass(
                            "com.unity3d.player.UnityPlayer")
                        val f = upClass.getDeclaredField("currentActivity")
                        f.isAccessible = true
                        f.set(null, activity)
                    } catch (e: Exception) {
                        BepInExLog.w("Failed to set currentActivity: ${e.message}")
                    }

                    val bundle = if (callFrame.args.isNotEmpty() &&
                        callFrame.args[0] is Bundle) {
                        callFrame.args[0] as Bundle
                    } else null

                    try { initializeFusion(activity, bundle) }
                    catch (t: Throwable) { BepInExLog.e("initializeFusion failed", t) }
                }
            })

            hookInstalled.set(true)
            BepInExLog.i("Launcher onCreate hook installed for $launcherClassName")
            true
        } catch (e: Exception) {
            BepInExLog.e("Failed to hook launcher onCreate: ${e.message}", e)
            false
        }
    }

    // Initialize Fusion

    private fun initializeFusion(launcherActivity: Activity?, bundle: Bundle?) {
        if (!fusionInitialized.compareAndSet(false, true)) return

        val config = preparedConfig
            ?: throw IllegalStateException("Fusion config was not prepared!")

        val launcherName = launcherActivity?.javaClass?.name ?: "pre-launch"
        BepInExLog.i("=== initializeFusion for $launcherName ===")

        try {
            NativeLibraryManager.addFusionLibrary("main")
            NativeLibraryManager.addFusionLibrary("fusion")
            NativeLibraryManager.addDataLibrary("il2cpp")
            NativeLibraryManager.addDataLibrary("unity")
            NativeLibraryManager.setupLibraryHooks(config)

            val stagedFile = FusionConfigStore.write(this, config)
            BepInExLog.i("Fusion config staged: ${stagedFile.absolutePath}")

            // Start floating log overlay if enabled in settings
            if (AppSettings.isFloatingLogInGameEnabled(this@BootstrapActivity)) {
                val pkg = this.targetPackage ?: "unknown"
                LogOverlayService.start(this@BootstrapActivity, pkg)
                BepInExLog.i("Started floating log overlay service")
            }
        } catch (t: Throwable) {
            BepInExLog.e("Failed to initialize Fusion", t)
        }
    }

    // Prepare Fusion state

    private fun prepareFusionState(
        targetPackage: String,
        gameContext: Context,
        useOriginalLibUnity: Boolean
    ): FusionConfig {
        val gameLibDir = gameContext.applicationInfo.nativeLibraryDir
        val appLibDir = applicationInfo.nativeLibraryDir

        // Per-game internal data dir
        val appDataDir = BepInExPaths.getAppDataDir(filesDir, targetPackage)

        // Per-game external storage (FusionCore pattern)
        val dataOnSdCard = BepInExPaths.getGameRootDir(targetPackage)

        BepInExLog.i("Paths:")
        BepInExLog.i("  gameLibDir:    $gameLibDir")
        BepInExLog.i("  appLibDir:     $appLibDir")
        BepInExLog.i("  appDataDir:    ${appDataDir.absolutePath}")
        BepInExLog.i("  dataOnSdCard:  ${dataOnSdCard.absolutePath}")

        appDataDir.mkdirs()
        dataOnSdCard.mkdirs()

        // Extract BepInEx to external storage
        val fileExtractor = FileExtractor(this)
        fileExtractor.extractBepInExIfNeeded(targetPackage) { status ->
            BepInExLog.i(status)
        }

        // Extract dotnet to internal storage
        fileExtractor.extractDotnetIfNeeded(targetPackage) { status ->
            BepInExLog.i(status)
        }

        val bepInExDir = BepInExPaths.getBepInExDir(targetPackage)
        val dotnetDir = BepInExPaths.getDotnetDir(filesDir, targetPackage)

        // Copy Unity Data files from game APK to internal storage
        val copiedData = BepInExPaths.getCopiedDataDir(filesDir, targetPackage)
        if (!copiedData.exists() || copiedData.list()?.isEmpty() != false) {
            BepInExLog.i("Copying game assets/bin/Data  -> ${copiedData.absolutePath}")
            try {
                copyGameDataAssets(gameContext, copiedData)
            } catch (e: Exception) {
                BepInExLog.e("Failed to copy Data assets (non-fatal)", e)
            }
        }

        // Apply global-metadata.dat override if present on external storage
        val overrideMetadata = File(dataOnSdCard, GLOBAL_METADATA_FILE)
        if (overrideMetadata.isFile) {
            BepInExLog.i("Applying global-metadata.dat override")
            val targetMetadata = File(File(copiedData, "Managed/Metadata"), GLOBAL_METADATA_FILE)
            try {
                targetMetadata.parentFile?.mkdirs()
                overrideMetadata.copyTo(targetMetadata, overwrite = true)
            } catch (e: Exception) {
                BepInExLog.e("Failed to apply global-metadata override", e)
            }
        }

        // Detect Unity version from game data (FusionCore VersionLookup)
        val unityVersion = tryLookupUnityVersion(copiedData)
            ?: BACKUP_UNITY_VERSION.also {
                BepInExLog.w("Failed to detect Unity version, using fallback: $BACKUP_UNITY_VERSION")
            }
        BepInExLog.i("Unity version: $unityVersion")

        // Download unity base libraries using Android's HTTP stack (not .NET's).
        // .NET's HttpClient crashes on Android 16 with SIGSEGV in
        // AndroidCryptoNative_SSLStreamCreate. FusionCore mirrors this pattern
        // in LibUnityDownloader.java for libunity.so.
        val unityLibsDir = File(bepInExDir, "unity-libs")
        BepInExLog.i("Ensuring unity base libraries for Unity $unityVersion...")
        val unityLibsReady = UnityLibsDownloader.ensureLibraries(unityLibsDir, unityVersion)
        if (!unityLibsReady) {
            BepInExLog.w("Failed to download unity base libraries  -- disabling auto-download in BepInEx.cfg")
            // Prevent BepInEx from attempting to download via .NET HttpClient (which crashes)
            patchBepInExConfigDisableDownload(bepInExDir)
        }

        // Apply active modpack (or clear for vanilla mode) with per-modpack state persistence
        val activeModpack = intent.getStringExtra(EXTRA_ACTIVE_MODPACK)
        val modpackManager = com.bepinex.android.modpack.ModpackManager()
        val previousActive = com.bepinex.android.settings.AppSettings.getActiveModpack(this, targetPackage)

        // Save whatever runtime generated from previous run back to its owner
        modpackManager.persistRuntimeState(targetPackage, previousActive)

        if (activeModpack.isNullOrEmpty()) {
            BepInExLog.i("Vanilla mode  -- restoring vanilla cfg/logs")
            modpackManager.clearActiveMods(targetPackage)
        } else {
            BepInExLog.i("Applying modpack: $activeModpack")
            modpackManager.applyModpack(targetPackage, activeModpack)
        }
        com.bepinex.android.settings.AppSettings.setActiveModpack(this, targetPackage, activeModpack)

        // Register game native libraries (match FusionCore: no exclusions)
        File(gameLibDir).listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("lib") && name.endsWith(".so") && name.length > 6) {
                val libName = name.substring(3, name.length - 3)
                NativeLibraryManager.addGameLibrary(libName)
            }
        }

        return FusionConfig(
            gameLibraryDirectory = gameLibDir,
            appLibraryDirectory = appLibDir,
            appDataDirectory = appDataDir.absolutePath,
            bepInExDirectory = bepInExDir.absolutePath,
            dotnetDirectory = dotnetDir.absolutePath,
            unityDataDirectory = copiedData.absolutePath,
            unityVersion = unityVersion,
            useOriginalLibUnity = useOriginalLibUnity
        )
    }

    // Unity version detection

    /**
     * Try to detect Unity version from game data files.
     * Ported from FusionCore's VersionLookup.java.
     */
    private fun tryLookupUnityVersion(dataFolder: File): String? {
        for ((fileName, offsets) in VERSION_LOOKUP_MAP) {
            val file = File(dataFolder, fileName)
            if (!file.isFile) continue

            try {
                RandomAccessFile(file, "r").use { reader ->
                    for (offset in offsets) {
                        if (offset < 0 || offset >= reader.length()) continue
                        reader.seek(offset.toLong())
                        val candidate = readAsciiString(reader, 32)
                        if (candidate != null && UNITY_VERSION_PATTERN.matcher(candidate).matches()) {
                            return candidate
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next file
            }
        }
        return null
    }

    private fun readAsciiString(reader: RandomAccessFile, maxLength: Int): String? {
        val builder = StringBuilder(maxLength)
        for (i in 0 until maxLength) {
            val b = reader.read()
            if (b == -1 || b == 0) break
            if (b < 0x20 || b > 0x7E) break
            builder.append(b.toChar())
        }
        return builder.toString().trim().ifEmpty { null }
    }

    // Asset copying

    private fun copyGameDataAssets(gameContext: Context, destDir: File) {
        destDir.mkdirs()
        try {
            copyAssetsRecursive(gameContext.assets, "bin/Data", destDir)
            val fileCount = destDir.walkTopDown().count { it.isFile }
            BepInExLog.i("Copied $fileCount Data files (recursive)")
        } catch (e: Exception) {
            BepInExLog.w("Game has no bin/Data assets: ${e.message}")
        }
    }

    private fun copyAssetsRecursive(
        am: android.content.res.AssetManager,
        assetPath: String,
        destDir: File
    ) {
        destDir.mkdirs()
        val entries = try { am.list(assetPath) } catch (e: Exception) { null } ?: return

        for (entry in entries) {
            val childPath = "$assetPath/$entry"
            try {
                am.open(childPath).use { input ->
                    val outFile = File(destDir, entry)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: java.io.FileNotFoundException) {
                copyAssetsRecursive(am, childPath, File(destDir, entry))
            } catch (e: Exception) {
                BepInExLog.w("Copy asset $childPath: ${e.message}")
            }
        }
    }

    // Config fixup

    /**
     * Sets `UnityBaseLibrariesSource` to empty in BepInEx.cfg so the managed
     * [Il2CppInteropManager.DownloadUnityAssemblies] returns early instead of
     * attempting an HTTPS download via .NET HttpClient (which SIGSEGVs on
     * Android 16 in AndroidCryptoNative_SSLStreamCreate).
     */
    private fun patchBepInExConfigDisableDownload(bepInExDir: File) {
        val configFile = File(bepInExDir, "config/BepInEx.cfg")
        if (!configFile.exists()) return

        try {
            val original = configFile.readText()
            // Replace any non-empty value with empty string.
            // BepInEx Il2CppInteropManager checks string.IsNullOrEmpty(source) and returns early.
            val patched = original.replace(
                Regex("^(UnityBaseLibrariesSource\\s*=\\s*)\\S.*$", RegexOption.MULTILINE),
                "$1"
            )
            if (patched != original) {
                configFile.writeText(patched)
                BepInExLog.i("Patched BepInEx.cfg: disabled UnityBaseLibrariesSource download")
            }
        } catch (e: Exception) {
            BepInExLog.w("Failed to patch BepInEx.cfg: ${e.message}")
        }
    }

    // UI helpers

    private fun resolveTargetOrientation(launcher: ComponentName): Int {
        return try {
            val info = packageManager.getActivityInfo(launcher, 0)
            if (info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                BepInExLog.i("Target orientation unspecified; defaulting to landscape")
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                info.screenOrientation
            }
        } catch (e: PackageManager.NameNotFoundException) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun failAndFinish(message: String, error: Throwable? = null) {
        if (error != null) BepInExLog.e(message, error) else BepInExLog.e(message)
        runOnMainThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun runOnMainThread(runnable: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            runOnUiThread(runnable)
        }
    }
}
