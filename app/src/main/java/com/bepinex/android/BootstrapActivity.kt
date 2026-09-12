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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bepinex.android.fusion.*
import com.bepinex.android.settings.AppSettings
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bootstrap activity that injects BepInEx into the target game process
 * via Pine hooks, custom libmain.so/libfusion.so, and CoreCLR bootstrap.
 */
class BootstrapActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity"
        const val EXTRA_ACTIVE_MODPACK = "active_modpack" // null/empty = vanilla
        const val EXTRA_BLOCK_UNITY_KILL = "block_unity_kill"

        private const val BACKUP_UNITY_VERSION = "2017.0.0"
        private const val GLOBAL_METADATA_FILE = "global-metadata.dat"

        /** Per-process: guard against re-installing Pine hooks on 2nd launch */
        private val hookInstalled = AtomicBoolean(false)
        /** Per-process: guard against re-running initializeFusion on 2nd launch */
        private val fusionInitialized = AtomicBoolean(false)
        /** Per-process: guard against re-installing base hooks on 2nd launch */
        private val baseHooksInstalled = AtomicBoolean(false)
    }

    private var preparedConfig: FusionConfig? = null
    private var targetPackage: String? = null

    private var tvStep: TextView? = null
    private var tvDetail: TextView? = null
    private var progressBar: ProgressBar? = null

    private fun updateProgress(step: String, detail: String = "", percent: Int = -1) {
        runOnUiThread {
            tvStep?.text = step
            tvDetail?.text = detail
            if (percent >= 0) progressBar?.progress = percent
        }
    }

    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bootstrap)

        tvStep = findViewById(R.id.tvStep)
        tvDetail = findViewById(R.id.tvDetail)
        progressBar = findViewById(R.id.progressBar)

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        tvDetail?.text = targetPackage
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
        updateProgress(getString(R.string.bootstrap_status_preparing), "", 5)
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: throw IllegalStateException("No launch intent for $targetPackage")

        val launcher = launchIntent.component
            ?: launchIntent.resolveActivity(packageManager)
            ?: throw IllegalStateException("Cannot resolve launcher for $targetPackage")

        BepInExLog.i("Game launcher: ${launcher.className}")
        val targetOrientation = resolveTargetOrientation(launcher)

        // 2. Create game context (with DEX access)
        updateProgress(getString(R.string.bootstrap_status_preparing), "", 10)
        val gameContext: Context = try {
            createPackageContext(targetPackage,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create game context", e)
        }

        BepInExLog.i("Game context: ${gameContext.packageCodePath}")

        // 3. Prepare Fusion state (paths, extract zips, copy data, detect version)
        val useOriginalLibUnity = intent.getBooleanExtra(EXTRA_USE_ORIGINAL_LIBUNITY, false)
        val blockUnityKill = intent.getBooleanExtra(
            EXTRA_BLOCK_UNITY_KILL,
            AppSettings.isUnityKillBlockEnabled(this, targetPackage)
        )
        preparedConfig = prepareFusionState(targetPackage, gameContext, useOriginalLibUnity)

        // 4. Register game native libraries (match FusionCore: no exclusions)
        updateProgress(getString(R.string.bootstrap_status_registering_libraries), "", 60)
        var gameLibDir = gameContext.applicationInfo.nativeLibraryDir
        if (gameLibDir.isNullOrEmpty()) {
            // createPackageContext sometimes yields an ApplicationInfo with an
            // empty nativeLibraryDir (observed on 2nd launch). Fall back to the
            // game's APK native dir derived from packageManager.
            BepInExLog.w("nativeLibraryDir is empty; falling back to packageManager path")
            gameLibDir = try {
                val appInfo = packageManager.getApplicationInfo(targetPackage, 0)
                appInfo.nativeLibraryDir
            } catch (_: Exception) { gameContext.applicationInfo.nativeLibraryDir }
        }
        File(gameLibDir).listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("lib") && name.endsWith(".so") && name.length > 6) {
                val libName = name.substring(3, name.length - 3)
                NativeLibraryManager.addGameLibrary(libName)
            }
        }

        // 5. Install base Pine hooks (per-process guard)
        updateProgress(getString(R.string.bootstrap_status_installing_hooks), "", 70)
        if (!baseHooksInstalled.get()) {
            BepInExLog.i("Installing Pine hooks...")
            try {
                ClassLoaderHooks.installHooks(gameContext.classLoader)
                PackageManagerHooks.installHooks(packageManager)
                InstrumentationHooks.install()
                UnityPlayerHooks.installHooks(gameContext, blockUnityKill)
                baseHooksInstalled.set(true)
                BepInExLog.i("Base hooks installed")
            } catch (e: Exception) {
                throw IllegalStateException("Failed to install base hooks", e)
            }
        } else {
            BepInExLog.i("Base hooks already installed (same process)")
        }

        // 5. Hook game launcher's onCreate (optional — some launchers inherit it)
        updateProgress(getString(R.string.bootstrap_status_installing_hooks), "", 85)
        val launcherClassName = launcher.className
        installLauncherOnCreateHook(gameContext, gameContext.classLoader, launcherClassName)

        // 5b. Start logcat capture in :game process (so MainActivity can read it after a crash)
        try {
            com.bepinex.android.log.GameLogcatCapture.start(targetPackage)
        } catch (e: Exception) {
            BepInExLog.w("GameLogcatCapture start failed (non-fatal): ${e.message}")
        }

        // 6. Start the registered stub. InstrumentationHooks restores the
        // target class in this process and UnityPlayerHooks supplies its
        // game-resource/Fusion storage context.
        updateProgress(getString(R.string.bootstrap_status_launching), "", 95)
        try {
            val launcherClass = gameContext.classLoader.loadClass(launcherClassName)
            BepInExLog.i("Starting game launcher: ${launcherClass.name}")
            initializeFusion(null, null)

            runOnMainThread {
                try {
                    val intent = Intent(this, launcherClass).apply {
                        putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation)
                    }
                    val wrapped = Intent(this, StubActivity::class.java).apply {
                        putExtra(InstrumentationHooks.EXTRA_IS_DYNAMIC_ACTIVITY, true)
                        putExtra(InstrumentationHooks.EXTRA_ORIGINAL_INTENT, intent)
                        putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation)
                    }
                    startActivity(wrapped)
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
        updateProgress(getString(R.string.bootstrap_status_extracting_runtime), "Preparing mod framework", 15)
        val fileExtractor = FileExtractor(this)
        fileExtractor.extractBepInExIfNeeded(targetPackage) { status ->
            BepInExLog.i(status)
        }

        // Extract dotnet to internal storage
        updateProgress(getString(R.string.bootstrap_status_extracting_runtime), "", 25)
        fileExtractor.extractDotnetIfNeeded(targetPackage) { status ->
            BepInExLog.i(status)
        }

        val bepInExDir = BepInExPaths.getBepInExDir(targetPackage)
        val dotnetDir = BepInExPaths.getDotnetDir(filesDir, targetPackage)

        // Copy Unity Data files from game APK to internal storage
        val copiedData = BepInExPaths.getCopiedDataDir(filesDir, targetPackage)
        val dataUnity3d = File(copiedData, "data.unity3d")
        if (!copiedData.exists() || copiedData.list()?.isEmpty() != false || !dataUnity3d.exists()) {
            BepInExLog.i("Copying game assets/bin/Data  -> ${copiedData.absolutePath}")
            try {
                copyGameDataAssets(gameContext, copiedData) { step, detail ->
                    updateProgress(getString(R.string.bootstrap_status_copy_assets), detail, 35)
                }
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
        updateProgress(getString(R.string.bootstrap_status_detecting_version), "", 45)
        val unityVersion = UnityVersionLookup.find(copiedData)
            ?: BACKUP_UNITY_VERSION.also {
                BepInExLog.w("Failed to detect Unity version, using fallback: $BACKUP_UNITY_VERSION")
            }
        BepInExLog.i("Unity version: $unityVersion")

        // Download unity base libraries using Android's HTTP stack (not .NET's).
        // .NET's HttpClient crashes on Android 16 with SIGSEGV in
        // AndroidCryptoNative_SSLStreamCreate. FusionCore mirrors this pattern
        // in LibUnityDownloader.java for libunity.so.
        updateProgress(getString(R.string.bootstrap_status_downloading_libunity), "", 50)
        val unityLibsDir = File(bepInExDir, "unity-libs")
        BepInExLog.i("Ensuring unity base libraries for Unity $unityVersion...")
        val unityLibsReady = UnityLibsDownloader.ensureLibraries(unityLibsDir, unityVersion) { detail ->
            updateProgress(getString(R.string.bootstrap_status_downloading_libunity), detail, 50)
        }
        if (!unityLibsReady) {
            BepInExLog.w("Failed to download unity base libraries  -- disabling auto-download in BepInEx.cfg")
            // Prevent BepInEx from attempting to download via .NET HttpClient (which crashes)
            patchBepInExConfigDisableDownload(bepInExDir)
        }

        // Download unstripped libunity.so if the user opted in
        val unityVersionKnown = unityVersion != BACKUP_UNITY_VERSION
        if (!useOriginalLibUnity && unityVersionKnown) {
            updateProgress(getString(R.string.bootstrap_status_downloading_libunity), "Unstripped libunity", 52)
            val targetGameAbi = resolveTargetGameAbi(gameLibDir)
            val normalizedAbi = LibUnityDownloader.normalizeAbiPublic(targetGameAbi)
            val normalizedVersion = unityVersion.trim().replace(Regex("[fp]\\d+$"), "")
            val unstrippedDir = File(appDataDir, "$normalizedVersion-$normalizedAbi")

            val libReady = LibUnityDownloader.downloadAndCacheSafely(
                unstrippedDir, unityVersion, targetGameAbi,
                object : LibUnityDownloader.DownloadProgressListener {
                    override fun onDownloadStarted(url: String, totalBytes: Long) {
                        val detail = if (totalBytes > 0) "0 / ${formatBytes(totalBytes)}" else "Connecting..."
                        updateProgress(getString(R.string.bootstrap_status_downloading_libunity), detail, 52)
                    }
                    override fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long) {
                        val detail = if (totalBytes > 0) {
                            "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
                        } else {
                            formatBytes(downloadedBytes)
                        }
                        updateProgress(getString(R.string.bootstrap_status_downloading_libunity), detail, 52)
                    }
                    override fun onDownloadFinished(success: Boolean, usedCache: Boolean) {
                        if (usedCache) {
                            BepInExLog.i("Using cached unstripped libunity")
                        } else if (success) {
                            BepInExLog.i("Successfully downloaded unstripped libunity")
                        } else {
                            BepInExLog.w("Failed to download unstripped libunity")
                        }
                    }
                }
            )

            if (libReady) {
                // Copy unstripped libunity.so to appDataDir where NativeLibraryManager will look
                val srcFile = File(unstrippedDir, "libunity.so")
                val destFile = File(appDataDir, "libunity.so")
                if (srcFile.exists() && !destFile.exists()) {
                    srcFile.copyTo(destFile, overwrite = true)
                    BepInExLog.i("Copied unstripped libunity to ${destFile.absolutePath}")
                }
            } else {
                BepInExLog.w("Unstripped libunity unavailable; using the game's original libunity.so")
            }
        } else if (!unityVersionKnown) {
            BepInExLog.w("Unity version unknown; skipping unstripped libunity download")
        }

        // Apply active modpack (or clear for vanilla mode) with per-modpack state persistence
        updateProgress(getString(R.string.bootstrap_status_preparing), "", 55)
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

    // Asset copying

    private fun copyGameDataAssets(gameContext: Context, destDir: File, onProgress: (String, String) -> Unit = { _, _ -> }) {
        destDir.mkdirs()
        try {
            // Count files first for progress
            val totalCount = countAssets(gameContext.assets, "bin/Data")
            var copiedCount = 0
            onProgress("Copying game data...", "0 / $totalCount files")
            copyAssetsRecursive(gameContext.assets, "bin/Data", destDir) {
                copiedCount++
                onProgress("Copying game data...", "$copiedCount / $totalCount files")
            }
            BepInExLog.i("Copied $copiedCount Data files (recursive)")
        } catch (e: Exception) {
            BepInExLog.w("Game has no bin/Data assets: ${e.message}")
        }
    }

    private fun countAssets(am: android.content.res.AssetManager, assetPath: String): Int {
        val entries = try { am.list(assetPath) } catch (e: Exception) { null } ?: return 0
        var count = 0
        for (entry in entries) {
            val childPath = "$assetPath/$entry"
            try {
                am.open(childPath).use { count++ }
            } catch (e: java.io.FileNotFoundException) {
                count += countAssets(am, childPath)
            }
        }
        return count
    }

    private fun copyAssetsRecursive(
        am: android.content.res.AssetManager,
        assetPath: String,
        destDir: File,
        onFileCopied: () -> Unit = {}
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
                    onFileCopied()
                }
            } catch (e: java.io.FileNotFoundException) {
                copyAssetsRecursive(am, childPath, File(destDir, entry), onFileCopied)
            } catch (e: Exception) {
                BepInExLog.w("Copy asset $childPath: ${e.message}")
            }
        }
    }

    /**
     * Resolves the target game's ABI by scanning its nativeLibraryDir.
     * Used by LibUnityDownloader to pick the right unstripped libunity.so.
     */
    private fun resolveTargetGameAbi(gameLibDir: String): String {
        val gameLibFile = File(gameLibDir)
        if (gameLibFile.exists()) {
            val soFiles = gameLibFile.listFiles()?.filter { it.name.endsWith(".so") } ?: emptyList()
            if (soFiles.isNotEmpty()) {
                // Infer ABI from filename prefix
                val firstLib = soFiles.first().name
                return when {
                    firstLib.contains("arm64") || firstLib.contains("aarch64") -> "arm64-v8a"
                    firstLib.contains("armeabi") -> "armeabi-v7a"
                    firstLib.contains("x86_64") || firstLib.contains("x64") -> "x86_64"
                    firstLib.contains("x86") || firstLib.contains("i686") -> "x86"
                    else -> android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                }
            }
        }
        return android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
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

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        return "%.1f %s".format(value, units[unitIndex])
    }
}
