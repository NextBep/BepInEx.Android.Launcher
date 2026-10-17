package com.bepinex.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bepinex.android.hooks.ActivityManagerHooks
import com.bepinex.android.hooks.GameRuntime
import com.bepinex.android.hooks.InstrumentationHooks
import com.bepinex.android.hooks.NotificationManagerHooks
import com.bepinex.android.hooks.PackageManagerHooks
import com.bepinex.android.log.GameLogcatCapture
import com.bepinex.android.modpack.ModpackManager
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.tools.FusionConfig
import com.bepinex.android.tools.GlobalMetadata
import com.bepinex.android.tools.Il2CppApiMapper
import com.bepinex.android.tools.NativeLibraryManager
import com.bepinex.android.tools.UnityBaseLibraries
import com.bepinex.android.tools.Utilities
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bootstrap activity that injects BepInEx into the target game.
 *
 * Runs in the `:game` process (see AndroidManifest.xml) so the whole game runs
 * isolated from the launcher UI process. The flow follows the FusionCore
 * architecture:
 *
 *   1. build a game classloader on top of ours ([GameRuntime.prepareGameContext])
 *   2. prepare per-game state: assets, BepInEx runtime, .NET runtime, libunity, config
 *   3. install the Java-side hooks (package manager / activity manager / instrumentation)
 *   4. wire up native library routing (libmain + libfusion)
 *   5. start the game through a dynamic [StubActivity] intent that [GameRuntime]'s
 *      instrumentation hook rewrites back into the real game launcher activity.
 */
class BootstrapActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_USE_ORIGINAL_LIBUNITY = "og_libunity"
        const val EXTRA_ACTIVE_MODPACK = "active_modpack"

        /**
         * Accepted for compatibility with the existing launcher UI; ignored because
         * the FusionCore architecture has no Pine hooks to block Unity's kill call.
         */
        const val EXTRA_BLOCK_UNITY_KILL = "block_unity_kill"

        /** Unity version used when detection fails; disables version-specific logic. */
        const val BACKUP_UNITY_VERSION = "2017.0.0"

        /** User-supplied il2cpp API deobfuscation map (placed in BepInEx/Deobfuscation). */
        const val IL2CPP_API_MAP = "il2cpp-api.map"

        /** Scoped runtime dirs: getDir("bepinex_runtime") holds per-game caches + dotnet. */
        private const val RUNTIME_DIR_NAME = "bepinex_runtime"

        private const val REQ_PICK_FILE = 0x4E42 // "NB"

        /** Hooks can only be installed once per `:game` process (it may be reused). */
        private val baseHooksInstalled = AtomicBoolean(false)
    }

    private var tvStep: TextView? = null
    private var tvDetail: TextView? = null
    private var progressBar: ProgressBar? = null

    private val selectedFileUri = AtomicReference<Uri?>(null)

    @Volatile
    private var filePickerLatch: CountDownLatch? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bootstrap)

        tvStep = findViewById(R.id.tvStep)
        tvDetail = findViewById(R.id.tvDetail)
        progressBar = findViewById(R.id.progressBar)

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        findViewById<TextView>(R.id.tvPackage)?.text = targetPackage ?: ""

        if (targetPackage.isNullOrEmpty()) {
            failAndFinish("No target package specified!")
            return
        }

        BepInExLog.i("=== BepInEx bootstrap for $targetPackage ===")
        setPhaseStatus(getString(R.string.bootstrap_status_preparing))

        // Let the loading screen render first, then run initialization off the UI thread.
        tvStep?.post {
            Thread({
                try {
                    runBootstrapFlow(targetPackage)
                } catch (t: Throwable) {
                    failAndFinish("Bootstrap failed: ${t.message}", t)
                }
            }, "bootstrap-flow").start()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK_FILE) {
            selectedFileUri.set(if (resultCode == RESULT_OK) data?.data else null)
            filePickerLatch?.countDown()
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ------------------------------------------------------------------ bootstrap flow

    private fun runBootstrapFlow(targetPackage: String) {
        // 1. Game context with code access
        var gameContext: Context = try {
            createPackageContext(
                targetPackage,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
        } catch (e: Exception) {
            failAndFinish("Failed to create package context for target package: $targetPackage", e)
            return
        }

        // 2. Game classloader (game dex first, our classes delegated to the parent)
        try {
            gameContext = GameRuntime.prepareGameContext(gameContext, classLoader)
        } catch (e: Exception) {
            failAndFinish("Failed to configure classes.", e)
            return
        }

        // 3. Resolve the game launcher activity
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            failAndFinish("No launch intent for target package: $targetPackage")
            return
        }
        val launcherComponent = launchIntent.component ?: launchIntent.resolveActivity(packageManager)
        if (launcherComponent == null) {
            failAndFinish("Failed to resolve launcher activity for target package: $targetPackage")
            return
        }
        val targetOrientation = resolveTargetOrientation(launcherComponent)

        // 4. Prepare all per-game state (paths, assets, runtime, libunity, config)
        val useOriginalLibUnity = intent.getBooleanExtra(EXTRA_USE_ORIGINAL_LIBUNITY, false)
        val config = try {
            prepareConfig(applicationContext, gameContext, launcherComponent, targetPackage, useOriginalLibUnity)
        } catch (t: Throwable) {
            failAndFinish("Failed while preparing BepInEx runtime.", t)
            return
        }

        val launcherClass = try {
            gameContext.classLoader.loadClass(launcherComponent.className)
        } catch (e: ClassNotFoundException) {
            failAndFinish("Failed to load launcher class: ${launcherComponent.className}", e)
            return
        }

        // 5. Install base hooks (once per process)
        setPhaseStatus(getString(R.string.bootstrap_status_installing_hooks))
        if (baseHooksInstalled.compareAndSet(false, true)) {
            try {
                PackageManagerHooks.install(applicationContext, gameContext)
                ActivityManagerHooks.install(applicationContext, gameContext)
                NotificationManagerHooks.install(applicationContext, gameContext)
                GameRuntime.install(gameContext)
                BepInExLog.i("Base hooks installed")
            } catch (e: Exception) {
                failAndFinish("Failed to install base hooks", e)
                return
            }
        } else {
            BepInExLog.i("Base hooks already installed (same process)")
        }

        // 6. Capture logcat in this :game process (crash diagnosis)
        try {
            GameLogcatCapture.start(targetPackage)
        } catch (e: Exception) {
            BepInExLog.w("GameLogcatCapture start failed (non-fatal): ${e.message}")
        }

        // 7. Wire native library routing, then launch through the dynamic stub activity
        setPhaseStatus(getString(R.string.bootstrap_status_launching))
        try {
            initializeFusion(config)
        } catch (e: Exception) {
            failAndFinish("Failed to initialize BepInEx library routing", e)
            return
        }

        runOnMainThread {
            try {
                GameRuntime.initializeApplication(gameContext, applicationContext)

                val gameIntent = Intent(this, launcherClass)
                val wrapped = Intent(this, StubActivity::class.java).apply {
                    putExtra(InstrumentationHooks.EXTRA_IS_DYNAMIC_ACTIVITY, true)
                    putExtra(InstrumentationHooks.EXTRA_ORIGINAL_INTENT, gameIntent)
                    putExtra(InstrumentationHooks.EXTRA_NEXT_CONFIG, config)
                    putExtra(InstrumentationHooks.EXTRA_TARGET_ORIENTATION, targetOrientation)
                }
                startActivity(wrapped)
                finish()
            } catch (t: Throwable) {
                failAndFinish(
                    "Failed to launch target app's launcher activity: ${launcherComponent.className}", t
                )
            }
        }
    }

    // ------------------------------------------------------------------ per-game state

    private fun prepareConfig(
        appContext: Context,
        gameContext: Context,
        launcherComponent: ComponentName,
        targetPackage: String,
        useOriginalLibUnityIn: Boolean
    ): FusionConfig {
        var useOriginalLibUnity = useOriginalLibUnityIn

        val gameLibDir = gameContext.applicationInfo.nativeLibraryDir
        val appLibDir = appContext.applicationInfo.nativeLibraryDir
        val targetGameAbi = resolveTargetGameAbi(gameLibDir)
            ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")

        val appDataDir = BepInExPaths.getAppDataDir(filesDir, targetPackage)
        val dataOnSdCard = Utilities.getExternalGameDirectory(targetPackage, null)
        val persistentDataDir = File(dataOnSdCard, "PersistentData")
        val bepInExDir = File(dataOnSdCard, "BepInEx")
        val codeCacheScoped = File(getDir(RUNTIME_DIR_NAME, Context.MODE_PRIVATE), targetPackage)

        BepInExLog.i("Paths:")
        BepInExLog.i("  gameLibDir:  $gameLibDir")
        BepInExLog.i("  appLibDir:   $appLibDir")
        BepInExLog.i("  appDataDir:  ${appDataDir.absolutePath}")
        BepInExLog.i("  codeCache:   ${codeCacheScoped.absolutePath}")
        BepInExLog.i("  bepInExDir:  ${bepInExDir.absolutePath}")

        appDataDir.mkdirs()
        persistentDataDir.mkdirs()
        codeCacheScoped.mkdirs()

        // Copy the game's APK assets (bin/Data etc.) to external storage
        updateProgress(getString(R.string.bootstrap_status_copy_assets), "Copying Unity data…", 10)
        val assetsDir = File(dataOnSdCard, "Assets")
        if (!Utilities.copyAssets(gameContext.applicationInfo, assetsDir)) {
            BepInExLog.e("Failed to copy Unity Data assets! BepInEx may not work correctly.")
        }
        val copiedData = File(assetsDir, "bin/Data")
        applyGlobalMetadataOverride(targetPackage, copiedData)

        // Detect the Unity engine version from the copied data
        updateProgress(getString(R.string.bootstrap_status_detecting_version), "", 25)
        var version = UnityVersionLookup.find(copiedData)
        if (version == null) {
            BepInExLog.e("Failed to determine Unity version! BepInEx may not work correctly.")
            version = BACKUP_UNITY_VERSION
            useOriginalLibUnity = true
        } else {
            BepInExLog.i("Determined Unity version: $version")
        }

        // Unstripped libunity (debug symbols) — downloaded with Android's HTTP stack
        if (useOriginalLibUnity) {
            BepInExLog.i("Skipping unstripped libunity download (using the game's original)")
        } else {
            updateProgress(getString(R.string.bootstrap_status_downloading_libunity), "", 35)
            val libReady = LibUnityDownloader.downloadAndCacheSafely(
                codeCacheScoped, version, targetGameAbi,
                object : LibUnityDownloader.DownloadProgressListener {
                    override fun onDownloadStarted(url: String, totalBytes: Long) {
                        val detail = if (totalBytes > 0) "0 / ${formatBytes(totalBytes)}" else "Connecting…"
                        updateProgress(getString(R.string.bootstrap_status_downloading_libunity), detail, 35)
                    }

                    override fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long) {
                        val detail = if (totalBytes > 0) {
                            "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
                        } else {
                            formatBytes(downloadedBytes)
                        }
                        updateProgress(getString(R.string.bootstrap_status_downloading_libunity), detail, 35)
                    }

                    override fun onDownloadFinished(success: Boolean, usedCache: Boolean) {
                        when {
                            usedCache -> BepInExLog.i("Using cached unstripped libunity")
                            success -> BepInExLog.i("Successfully downloaded unstripped libunity")
                            else -> BepInExLog.w("Failed to download unstripped libunity")
                        }
                    }
                }
            )
            if (libReady) {
                BepInExLog.i("Unstripped libunity ready at ${codeCacheScoped.absolutePath}")
            } else {
                BepInExLog.w("Unstripped libunity unavailable; falling back to the game's original libunity.so")
                useOriginalLibUnity = true
            }
        }

        // Extract the BepInEx runtime (config/ is preserved across launches)
        updateProgress(getString(R.string.bootstrap_status_extracting_runtime), "", 50)
        Utilities.extractZipFromAssets(appContext, "BepInEx.Android.zip", bepInExDir, "config/")

        // Extract the .NET runtime (skipped while the version/size stamp matches)
        FileExtractor(this).extractDotnetIfNeeded { detail ->
            updateProgress(getString(R.string.bootstrap_status_extracting_runtime), detail, 55)
        }

        // Unity base libraries for Il2CppInterop (pre-downloaded with Android's HTTP stack)
        updateProgress(getString(R.string.bootstrap_status_preparing_unity_libraries), "", 60)
        ensureUnityBaseLibraries(bepInExDir, version)

        // Apply the active modpack (or clear everything for vanilla mode)
        updateProgress(getString(R.string.bootstrap_status_preparing), "Applying modpack…", 70)
        val activeModpack = intent.getStringExtra(EXTRA_ACTIVE_MODPACK)
        val modpackManager = ModpackManager()
        val previousActive = AppSettings.getActiveModpack(this, targetPackage)
        modpackManager.persistRuntimeState(targetPackage, previousActive)
        if (activeModpack.isNullOrEmpty()) {
            BepInExLog.i("Vanilla mode — restoring vanilla cfg and plugins")
            modpackManager.clearActiveMods(targetPackage)
        } else {
            BepInExLog.i("Applying modpack: $activeModpack")
            modpackManager.applyModpack(targetPackage, activeModpack)
        }
        AppSettings.setActiveModpack(this, targetPackage, activeModpack)

        // Obfuscated IL2CPP exports support
        updateProgress(getString(R.string.bootstrap_status_preparing), "Checking il2cpp api map…", 75)
        val deobfuscationDir = File(bepInExDir, "Deobfuscation")
        val il2cppMap = resolveIl2CppApiMap(gameLibDir, codeCacheScoped, useOriginalLibUnity, deobfuscationDir)

        return FusionConfig(
            targetPackage,
            launcherComponent.flattenToString(),
            gameLibDir,
            appLibDir,
            appDataDir.absolutePath,
            codeCacheScoped.absolutePath,
            bepInExDir.absolutePath,
            dotnetDir().absolutePath,
            copiedData.absolutePath,
            version,
            useOriginalLibUnity,
            false,
            emptyArray<String>(),
            emptyArray<String>(),
            il2cppMap
        )
    }

    /**
     * Ensures the Unity base libraries ZIP is cached in `unity-libs` before BepInEx
     * starts.
     *
     * The managed side would otherwise download the ZIP through .NET HttpClient,
     * which crashes on Android 16 (AndroidCryptoNative_SSLStreamCreate SIGSEGV).
     * We pre-download with Android's HTTP stack and let managed code extract the
     * cached ZIP. If neither the exact version nor an older fallback exists, the
     * config source is emptied so BepInEx skips the download entirely instead of
     * crashing.
     */
    private fun ensureUnityBaseLibraries(bepInExDir: File, unityVersion: String) {
        val unityLibsDir = File(bepInExDir, "unity-libs")
        val progress = { detail: String ->
            updateProgress(getString(R.string.bootstrap_status_preparing_unity_libraries), detail, 60)
        }

        var ready = UnityLibsDownloader.ensureLibraries(unityLibsDir, unityVersion, progress)
        if (ready) {
            // Restore/clean the config source in case a previous run left it disabled
            // or pointing at a fallback release.
            UnityBaseLibraries.resolve(bepInExDir, unityVersion)
            return
        }

        // Exact version unavailable — resolve the closest older release and pre-download it.
        val resolvedUrl = UnityBaseLibraries.resolve(bepInExDir, unityVersion)
        val resolvedName = resolvedUrl?.substringAfterLast('/')
        if (!resolvedName.isNullOrEmpty()) {
            val resolvedVersion = resolvedName.removeSuffix(".zip")
            BepInExLog.i("Pre-downloading fallback Unity base libraries: $resolvedName")
            ready = UnityLibsDownloader.ensureLibraries(unityLibsDir, resolvedVersion, progress)
        }

        if (!ready) {
            BepInExLog.w("Unity base libraries unavailable — disabling managed auto-download in BepInEx.cfg")
            patchBepInExConfigDisableDownload(bepInExDir)
        }
    }

    // ------------------------------------------------------------------ il2cpp api map

    private fun resolveIl2CppApiMap(
        gameLibDir: String,
        codeCacheScoped: File,
        useOriginalLibUnity: Boolean,
        deobfuscationDir: File
    ): Map<String, String> {
        val gameLibIl2Cpp = File(gameLibDir, "libil2cpp.so")
        val cachedLibUnity = File(codeCacheScoped, "libunity.so")

        if (!useOriginalLibUnity) {
            try {
                val map = Il2CppApiMapper.prepare(File(gameLibDir, "libunity.so"), gameLibIl2Cpp, cachedLibUnity)
                if (map.isNotEmpty()) return map
            } catch (e: IOException) {
                BepInExLog.e("Failed to map obfuscated il2cpp exports automatically", e)
            }
        }

        try {
            if (!Il2CppApiMapper.hasObfuscatedExports(gameLibIl2Cpp)) {
                return emptyMap()
            }
        } catch (e: IOException) {
            BepInExLog.e("Failed to read il2cpp exports", e)
            return emptyMap()
        }

        val mapFile = File(deobfuscationDir, IL2CPP_API_MAP)
        if (!mapFile.isFile) {
            val selected = promptForFile(R.string.bootstrap_request_il2cpp_map, "map")
            if (selected == null) {
                runOnMainThread {
                    Toast.makeText(
                        this,
                        "WARNING: no il2cpp api deobfuscation map selected. Mods may fail to load.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return emptyMap()
            }
            try {
                copyUriToFile(selected, mapFile)
            } catch (e: IOException) {
                BepInExLog.e("Couldn't copy the deobfuscated il2cpp api map!", e)
                return emptyMap()
            }
        }

        val map = readIl2CppMap(deobfuscationDir)
        if (!useOriginalLibUnity && map.isNotEmpty()) {
            try {
                Il2CppApiMapper.patchLibUnity(cachedLibUnity, map)
            } catch (e: IOException) {
                BepInExLog.e("Failed to apply il2cpp api map to libunity", e)
            }
        }
        return map
    }

    private fun readIl2CppMap(deobfuscationDir: File): Map<String, String> {
        val mapFile = File(deobfuscationDir, IL2CPP_API_MAP)
        val map = HashMap<String, String>()
        if (!mapFile.isFile) return map

        try {
            mapFile.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("//")) {
                    val colonIndex = line.indexOf(':')
                    if (colonIndex > 0) {
                        val normal = line.substring(0, colonIndex).trim()
                        val deobfuscated = line.substring(colonIndex + 1).trim()
                        if (normal.isNotEmpty() && deobfuscated.isNotEmpty()) {
                            map[normal] = deobfuscated
                        }
                    }
                }
            }
        } catch (e: IOException) {
            BepInExLog.e("Failed to parse IL2CPP API map file: ${mapFile.absolutePath}", e)
        }
        return map
    }

    // ------------------------------------------------------------------ file picker

    private fun promptForFile(messageResId: Int, extension: String?): Uri? {
        filePickerLatch = CountDownLatch(1)
        selectedFileUri.set(null)

        val cleanExt = extension?.trim()?.lowercase(Locale.ROOT)?.replace(".", "") ?: ""

        runOnMainThread {
            if (isFinishing) {
                filePickerLatch?.countDown()
                return@runOnMainThread
            }
            Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show()

            val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (cleanExt.isNotEmpty()) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(cleanExt) ?: "*/*"
                } else {
                    "*/*"
                }
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(pickerIntent, REQ_PICK_FILE)
            } catch (e: Exception) {
                BepInExLog.e("Failed to launch file picker for extension: $extension", e)
                filePickerLatch?.countDown()
            }
        }

        try {
            filePickerLatch?.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            BepInExLog.e("Interrupted while waiting for file picker result", e)
            return null
        }

        val resultUri = selectedFileUri.get()
        if (resultUri != null && cleanExt.isNotEmpty()) {
            val fileName = getFileNameFromUri(resultUri)
            if (fileName != null && !fileName.lowercase(Locale.ROOT).endsWith(".$cleanExt")) {
                BepInExLog.w("Selected file '$fileName' does not match required extension: .$cleanExt")
                runOnMainThread {
                    Toast.makeText(this, "Selected file must be a .$cleanExt file", Toast.LENGTH_LONG).show()
                }
                return null
            }
        }
        return resultUri
    }

    private fun getFileNameFromUri(uri: Uri?): String? {
        if (uri == null) return null
        if ("content" == uri.scheme) {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrEmpty()) return name
                        }
                    }
                }
            } catch (e: Exception) {
                BepInExLog.w("Failed to query display name for URI: $uri")
            }
        }
        val path = uri.path
        if (path != null) {
            val cut = path.lastIndexOf('/')
            return if (cut != -1) path.substring(cut + 1) else path
        }
        return null
    }

    private fun copyUriToFile(sourceUri: Uri, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create parent directory: ${parent.absolutePath}")
        }

        val input = contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Unable to open input stream for URI: $sourceUri")
        input.use { inStream ->
            FileOutputStream(target, false).use { out ->
                inStream.copyTo(out)
            }
        }
    }

    // ------------------------------------------------------------------ extraction

    private fun dotnetDir(): File =
        File(getDir(RUNTIME_DIR_NAME, Context.MODE_PRIVATE), "dotnet")

    private fun applyGlobalMetadataOverride(targetPackage: String, copiedData: File) {
        if (!GlobalMetadata.hasValidOverride(targetPackage)) return

        val target = File(copiedData, "Managed/Metadata/" + GlobalMetadata.FILE_NAME)
        try {
            target.parentFile?.mkdirs()
            FileInputStream(GlobalMetadata.getOverrideFile(targetPackage)).use { input ->
                FileOutputStream(target, false).use { output -> input.copyTo(output) }
            }
            BepInExLog.i("Applied global-metadata override to ${target.absolutePath}")
        } catch (e: IOException) {
            BepInExLog.e("Failed to apply global-metadata override", e)
        }
    }

    // ------------------------------------------------------------------ native routing

    private fun initializeFusion(config: FusionConfig) {
        BepInExLog.i("Initializing BepInEx for ${config.gamePackageId} via ${config.gameLauncherName}")

        try {
            NativeLibraryManager.addFusionLibrary("main")
            NativeLibraryManager.addFusionLibrary("fusion")
            NativeLibraryManager.addCacheLibrary("il2cpp")
            NativeLibraryManager.addCacheLibrary("unity")
            NativeLibraryManager.setupLibraryHooks(config)
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to initialize BepInEx library routing", t)
        }
    }

    /**
     * Fallback for devices where the Unity base libraries ZIP cannot be fetched at
     * all: empty the source so managed code skips the download (it checks for an
     * empty source before doing anything) instead of crashing in .NET HttpClient.
     */
    private fun patchBepInExConfigDisableDownload(bepInExDir: File) {
        val configFile = File(bepInExDir, "config/BepInEx.cfg")
        if (!configFile.exists()) return

        try {
            val original = configFile.readText()
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

    // ------------------------------------------------------------------ helpers

    private fun resolveTargetOrientation(launcher: ComponentName): Int {
        return try {
            val info = packageManager.getActivityInfo(launcher, 0)
            if (info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                BepInExLog.i("Target orientation unspecified; defaulting to landscape")
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                info.screenOrientation
            }
        } catch (e: NameNotFoundException) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun resolveTargetGameAbi(gameLibDir: String?): String? {
        if (gameLibDir.isNullOrEmpty()) return null
        return File(gameLibDir).name.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ UI helpers

    private fun updateProgress(step: String, detail: String = "", percent: Int = -1) {
        runOnUiThread {
            tvStep?.text = step
            tvDetail?.text = detail
            if (percent >= 0) progressBar?.progress = percent
        }
    }

    private fun setPhaseStatus(status: String) {
        updateProgress(status, "", -1)
    }

    private fun failAndFinish(message: String, error: Throwable? = null) {
        if (error != null) BepInExLog.e(message, error) else BepInExLog.e(message)
        runOnMainThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            tvStep?.text = getString(R.string.bootstrap_status_error)
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
