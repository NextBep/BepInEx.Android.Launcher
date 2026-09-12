package com.bepinex.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.LocaleList
import android.provider.Settings
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.bepinex.android.ui.components.CrashRecoveryDialog
import com.bepinex.android.ui.components.UpdateDialog
import com.bepinex.android.log.BepInExLogReader
import com.bepinex.android.update.UpdateChecker
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.navigation.BepInExNavHost
import com.bepinex.android.ui.theme.BepInExTheme
import kotlinx.coroutines.*
import java.io.File

/**
 * Main launcher Activity  -- Compose UI for multi-game BepInEx mod management.
 *
 * On launch, auto-detects installed Unity IL2CPP games via [GameDetector].
 * User selects a game, manages mods, and launches it through [BootstrapActivity].
 */
class MainActivity : ComponentActivity() {

    private lateinit var fileExtractor: FileExtractor
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Per-game state
    private var detectedGames = listOf<GameDetector.DetectedGame>()
    private var selectedGame: GameDetector.DetectedGame? = null
    private var isScanning = true
    private var isExtracting = false
    private var extractionStatus = ""
    private var storagePermissionGranted = false
    private var pendingCrash: CrashDiagnostics.PendingLaunch? = null
    private var leftLauncher = false
    private var updateInfo: UpdateChecker.UpdateInfo? = null

    // Settings state
    private var themeMode = AppSettings.ThemeMode.SYSTEM
    private var language = AppSettings.Language.SYSTEM
    private var dynamicColor = true
    private var animationDisabled = false

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkStoragePermission() }

    companion object {
        /** Saved across activity recreations (e.g. language switch) */
        private var savedPackageName: String? = null
    }

    override fun attachBaseContext(newBase: Context?) {
        val ctx = newBase ?: return super.attachBaseContext(newBase)
        val lang = AppSettings.getLanguage(ctx)
        val locale = when (lang) {
            AppSettings.Language.ENGLISH -> Locale.forLanguageTag("en")
            AppSettings.Language.CHINESE -> Locale.forLanguageTag("zh-CN")
            AppSettings.Language.SYSTEM -> return super.attachBaseContext(newBase)
        }
        val config = Configuration(ctx.resources.configuration)
        config.setLocales(LocaleList(locale))
        super.attachBaseContext(ctx.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize settings first (before any Compose rendering)
        AppSettings.initialize(this)
        themeMode = AppSettings.getThemeMode(this)
        language = AppSettings.getLanguage(this)
        dynamicColor = AppSettings.isDynamicColorEnabled(this)
        animationDisabled = AppSettings.isAnimationDisabled(this)

        BepInExLog.init(this)
        BepInExLog.i("=== BepInEx Launcher ===")
        BepInExLog.i("Device: ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.SDK_INT}")

        fileExtractor = FileExtractor(this)
        pendingCrash = CrashDiagnostics.pending(this)

        checkStoragePermission()
        handleSharedText(intent)
        checkForUpdates()
    }

    // Storage permission

    private fun checkStoragePermission() {
        storagePermissionGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        if (!storagePermissionGranted) {
            BepInExLog.w("MANAGE_EXTERNAL_STORAGE not granted  -- showing permission dialog")
            Toast.makeText(this, "Need storage permission to extract BepInEx files", Toast.LENGTH_LONG).show()
            requestStoragePermission()
        } else {
            BepInExLog.i("Storage permission granted")
            startGameDetection()
            // Initial render
            render()
        }
    }

    private fun requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            storagePermissionLauncher.launch(intent)
        } catch (e: Exception) {
            BepInExLog.e("Failed to open storage permission settings", e)
            Toast.makeText(this, "Please grant 'All files access' in app settings", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    override fun onPause() {
        leftLauncher = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (leftLauncher) {
            CrashDiagnostics.clearPending(this)
            pendingCrash = null
            leftLauncher = false
        }
    }

    override fun onDestroy() {
        BepInExLogReader.stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    // Share message handling

    /**
     * Handle incoming shared text (e.g. browser sharing the OAuth callback URL).
     * Stores to /BepInEx_Android/share_messages.txt for AuthFix to read.
     */
    private fun handleSharedText(intent: Intent) {
        BepInExLog.i("handleSharedText: action=${intent.action}, type=${intent.type}")

        if (intent.action != Intent.ACTION_SEND) {
            BepInExLog.w("Not ACTION_SEND, ignoring")
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
        if (sharedText.isNullOrEmpty()) {
            BepInExLog.w("No EXTRA_TEXT or EXTRA_HTML_TEXT in share")
            return
        }

        BepInExLog.i("Shared text: ${sharedText.take(200)}")

        if (!sharedText.contains("accounts.innersloth.com")
            && !sharedText.contains("token=")) {
            BepInExLog.w("Not an Innersloth/token URL, ignoring")
            return
        }

        try {
            val shareFile = File(
                Environment.getExternalStorageDirectory(),
                "BepInEx_Android/share_messages.txt"
            )
            shareFile.parentFile?.mkdirs()
            shareFile.writeText(sharedText)
            BepInExLog.i("Share message saved (${sharedText.length} chars)")
            Toast.makeText(this, getString(R.string.share_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            BepInExLog.e("Failed to save share message", e)
        }
    }

    // Game detection

    private fun startGameDetection() {
        scope.launch {
            isScanning = true
            render()

            try {
                detectedGames = GameDetector.detectGames(this@MainActivity)
                BepInExLog.i("Detected ${detectedGames.size} Unity IL2CPP game(s)")

                if (selectedGame == null && detectedGames.isNotEmpty()) {
                    selectGame(detectedGames.first())
                }
            } catch (e: Exception) {
                BepInExLog.e("Game detection failed", e)
            }

            isScanning = false
            render()

            // Restore selected game after activity recreation (e.g. language switch)
            val saved = savedPackageName
            if (saved != null) {
                savedPackageName = null
                detectedGames.find { it.packageName == saved }?.let { selectGame(it) }
            }
        }
    }

    private fun selectGame(game: GameDetector.DetectedGame) {
        selectedGame = game
        BepInExLog.i("Selected: ${game.label} (${game.packageName})")

        if (!fileExtractor.isFrameworkReady(game.packageName)) {
            startExtraction(game.packageName)
        }

        render()
    }

    // Framework extraction

    private fun startExtraction(packageName: String) {
        isExtracting = true
        extractionStatus = getString(R.string.extracting)
        render()

        scope.launch(Dispatchers.IO) {
            try {
                fileExtractor.extractBepInExIfNeeded(packageName) { status ->
                    extractionStatus = status
                }
                fileExtractor.extractDotnetIfNeeded(packageName) { status ->
                    extractionStatus = status
                }
                BepInExLog.i("Framework extraction complete for $packageName")
            } catch (e: Exception) {
                BepInExLog.e("Extraction failed", e)
            }
            withContext(Dispatchers.Main) {
                isExtracting = false
                extractionStatus = ""
                render()
            }
        }
    }

    // Launch

    private fun launchGame(modpackName: String? = null) {
        val game = selectedGame ?: return

        if (!fileExtractor.isFrameworkReady(game.packageName)) {
            Toast.makeText(this, getString(R.string.launch_wait_extraction), Toast.LENGTH_SHORT).show()
            if (!isExtracting) startExtraction(game.packageName)
            return
        }

        BepInExLog.i("=== Launching ${game.label} (modpack: ${modpackName ?: "vanilla"}) via BootstrapActivity ===")

        try {
            CrashDiagnostics.markLaunch(this, game.packageName)
            val intent = Intent(this, BootstrapActivity::class.java).apply {
                putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, game.packageName)
                putExtra(BootstrapActivity.EXTRA_USE_ORIGINAL_LIBUNITY,
                    !AppSettings.isUseUnstrippedLibUnity(this@MainActivity, game.packageName))
                putExtra(
                    BootstrapActivity.EXTRA_BLOCK_UNITY_KILL,
                    AppSettings.isUnityKillBlockEnabled(this@MainActivity, game.packageName)
                )
                modpackName?.let { putExtra(BootstrapActivity.EXTRA_ACTIVE_MODPACK, it) }
            }
            startActivity(intent)
        } catch (e: Exception) {
            BepInExLog.e("Launch failed", e)
            Toast.makeText(this, getString(R.string.launch_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun exportCrashDiagnostics(packageName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val file = CrashDiagnostics.export(this@MainActivity, packageName)
                CrashDiagnostics.clearPending(this@MainActivity)
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.provider",
                        file
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "BepInEx diagnostics: $packageName")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, getString(R.string.crash_export)))
                }
            } catch (e: Exception) {
                BepInExLog.e("Failed to export diagnostics", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.crash_export_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Settings actions

    private fun onThemeChanged(mode: AppSettings.ThemeMode) {
        themeMode = mode
        AppSettings.setThemeMode(this, mode)
        // Re-render with new theme
        runOnUiThread { render() }
    }

    private fun onLanguageChanged(lang: AppSettings.Language) {
        language = lang
        AppSettings.setLanguage(this, lang)
        savedPackageName = selectedGame?.packageName
        recreate()
    }

    private fun onClearBepInEx(packageName: String) {
        val dir = BepInExPaths.getBepInExDir(packageName)
        if (dir.exists()) {
            dir.deleteRecursively()
            BepInExLog.i("Cleared BepInEx: ${dir.absolutePath}")
            Toast.makeText(this, getString(R.string.done), Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun onClearDotnet(packageName: String) {
        val dotnetDir = BepInExPaths.getDotnetDir(filesDir, packageName)
        val dataDir = BepInExPaths.getCopiedDataDir(filesDir, packageName)
        if (dotnetDir.exists()) dotnetDir.deleteRecursively()
        if (dataDir.exists()) dataDir.deleteRecursively()
        BepInExLog.i("Cleared .NET data for $packageName")
        Toast.makeText(this, getString(R.string.done), Toast.LENGTH_SHORT).show()
    }

    private fun onClearLibUnity(packageName: String) {
        val appDataDir = BepInExPaths.getAppDataDir(filesDir, packageName)
        val libunityDir = java.io.File(appDataDir, "libunity")
        val resId = if (libunityDir.exists()) {
            libunityDir.deleteRecursively()
            BepInExLog.i("Cleared libunity cache: ${libunityDir.absolutePath}")
            R.string.clear_libunity_done
        } else {
            BepInExLog.i("Clear libunity: nothing to clear at ${libunityDir.absolutePath}")
            R.string.clear_libunity_none
        }
        Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show()
    }

    private fun onCopyGameResources(packageName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val gameContext = createPackageContext(packageName,
                    android.content.Context.CONTEXT_IGNORE_SECURITY or android.content.Context.CONTEXT_INCLUDE_CODE)
                // This delegate to BootstrapActivity's logic via reflection or direct copy
                // For now, just trigger re-extraction by deleting Data_copy
                val dataDir = BepInExPaths.getCopiedDataDir(filesDir, packageName)
                if (dataDir.exists()) dataDir.deleteRecursively()
                dataDir.mkdirs()
                // Copy will happen on next launch via BootstrapActivity
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        "Resources will be copied on next launch", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                BepInExLog.e("Failed to copy resources", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkForUpdates() {
        scope.launch(Dispatchers.IO) {
            val info = UpdateChecker.fetchLatestRelease()
            withContext(Dispatchers.Main) {
                if (info != null) {
                    val currentVersion = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                    } catch (_: Exception) { "" }

                    if (UpdateChecker.hasUpdate(currentVersion, info.version)) {
                        updateInfo = info
                    }
                }
            }
        }
    }

    // UI render

    private fun render() {
        setContent {
            BepInExTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                val crash = pendingCrash
                BepInExNavHost(
                    scope = scope,
                    detectedGames = detectedGames,
                    selectedGame = selectedGame,
                    isScanning = isScanning,
                    isFrameworkReady = selectedGame?.let {
                        fileExtractor.isFrameworkReady(it.packageName)
                    } ?: false,
                    isExtracting = isExtracting,
                    extractionStatus = extractionStatus,
                    themeMode = themeMode,
                    language = language,
                    dynamicColor = dynamicColor,
                    animationDisabled = animationDisabled,
                    onSelectGame = { selectGame(it) },
                    onRescan = {
                        GameDetector.invalidateCache()
                        startGameDetection()
                    },
                    onLaunch = { modpackName -> launchGame(modpackName) },
                    onThemeChanged = { onThemeChanged(it) },
                    onLanguageChanged = { onLanguageChanged(it) },
                    onDynamicColorChanged = {
                        dynamicColor = it
                        AppSettings.setDynamicColorEnabled(this@MainActivity, it)
                    },
                    onAnimationDisabledChanged = {
                        animationDisabled = it
                        AppSettings.setAnimationDisabled(this@MainActivity, it)
                    },
                    onClearBepInEx = { onClearBepInEx(it) },
                    onClearDotnet = { onClearDotnet(it) },
                    onClearLibUnity = { onClearLibUnity(it) },
                    onCopyGameResources = { onCopyGameResources(it) }
                )
                if (crash != null) {
                    CrashRecoveryDialog(
                        packageName = crash.packageName,
                        onExport = {
                            pendingCrash = null
                            exportCrashDiagnostics(crash.packageName)
                        },
                        onDismiss = {
                            pendingCrash = null
                            CrashDiagnostics.clearPending(this@MainActivity)
                        }
                    )
                }
                val currentUpdateInfo = updateInfo
                if (currentUpdateInfo != null) {
                    val currentVersion = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                    } catch (_: Exception) { "" }
                    UpdateDialog(
                        updateInfo = currentUpdateInfo,
                        currentVersion = currentVersion,
                        onDismiss = { updateInfo = null }
                    )
                }
            }
        }
    }
}
