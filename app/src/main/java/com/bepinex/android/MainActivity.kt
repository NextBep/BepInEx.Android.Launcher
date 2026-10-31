package com.bepinex.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.app.ActivityManager
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
import com.bepinex.android.update.AnnouncementDialog
import com.bepinex.android.update.BlockedDialog
import com.bepinex.android.update.CrashDialog
import com.bepinex.android.update.UpdateDialog
import com.bepinex.android.update.openUpdateUrl
import com.bepinex.android.log.BepInExLogReader
import com.bepinex.android.update.UpdateChecker
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.navigation.BepInExNavHost
import com.bepinex.android.ui.theme.BepInExTheme
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile

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
    private var detectedGames by mutableStateOf(listOf<GameDetector.DetectedGame>())
    private var selectedGame by mutableStateOf<GameDetector.DetectedGame?>(null)
    private var isScanning by mutableStateOf(true)
    private var isExtracting by mutableStateOf(false)
    private var extractionStatus by mutableStateOf("")
    private var storagePermissionGranted by mutableStateOf(false)
    // Crash detection state
    private var crashMonitorJob: Job? = null
    private var gameProcessAlive by mutableStateOf(false)
    private var gameProcessStartedAtMillis = 0L
    private var showCrashDialog by mutableStateOf(false)
    private var crashInfo by mutableStateOf<CrashInfo?>(null)
    private var leftLauncher = false
    private var updateInfo by mutableStateOf<UpdateChecker.UpdateInfo?>(null)
    private var showAnnouncement by mutableStateOf(false)
    private var showUpdate by mutableStateOf(false)
    private var showBlocked by mutableStateOf(false)
    private var pendingAnnouncementToShow by mutableStateOf(false)

    // Settings state
    private var themeMode by mutableStateOf(AppSettings.ThemeMode.SYSTEM)
    private var language by mutableStateOf(AppSettings.Language.SYSTEM)
    private var dynamicColor by mutableStateOf(false)
    private var animationDisabled by mutableStateOf(false)

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
            AppSettings.Language.CHINESE_TW -> Locale.forLanguageTag("zh-TW")
            AppSettings.Language.SYSTEM -> return super.attachBaseContext(newBase)
        }
        val config = Configuration(ctx.resources.configuration)
        config.setLocales(LocaleList(locale))
        super.attachBaseContext(ctx.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A native crash cannot execute a finally block; archive the previous
        // run before initializing and appending to the current launcher log.
        DebugCrashCollector.collect(this)

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

        checkStoragePermission()
        handleSharedText(intent)
        checkForUpdates()
        setupContent()
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
            leftLauncher = false
            // The game process may have died while we were in the background.
            selectedGame?.let { game -> maybeReportGameCrash(game.packageName) }
        }
    }

    override fun onDestroy() {
        crashMonitorJob?.cancel()
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
    }

    // Framework extraction

    private fun startExtraction(packageName: String) {
        isExtracting = true
        extractionStatus = getString(R.string.extracting)

        scope.launch(Dispatchers.IO) {
            try {
                fileExtractor.extractBepInExIfNeeded(packageName) { status ->
                    extractionStatus = status
                }
                fileExtractor.extractDotnetIfNeeded { status ->
                    extractionStatus = status
                }
                BepInExLog.i("Framework extraction complete for $packageName")
            } catch (e: Exception) {
                BepInExLog.e("Extraction failed", e)
            }
            withContext(Dispatchers.Main) {
                isExtracting = false
                extractionStatus = ""
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

        scope.launch {
            try {
                crashMonitorJob?.cancel()
                gameProcessAlive = false
                gameProcessStartedAtMillis = System.currentTimeMillis()
                val intent = Intent(this@MainActivity, BootstrapActivity::class.java).apply {
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
                startCrashMonitor(game.packageName)
            } catch (e: Exception) {
                BepInExLog.e("Launch failed", e)
                Toast.makeText(this@MainActivity, getString(R.string.launch_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Crash detection (ported from the upstream BepInEx Android launcher)

    private fun startCrashMonitor(packageName: String) {
        crashMonitorJob?.cancel()
        gameProcessAlive = true
        crashMonitorJob = scope.launch(Dispatchers.IO) {
            delay(3000L)
            var sawGameProcess = false
            while (isActive && gameProcessAlive) {
                val running = isLauncherGameProcessRunning()
                if (running) sawGameProcess = true
                if (sawGameProcess && !running) {
                    reportGameCrash(packageName)
                    break
                }
                delay(2000L)
            }
        }
    }

    private fun maybeReportGameCrash(packageName: String) {
        if (!gameProcessAlive) return
        if (isLauncherGameProcessRunning()) return
        scope.launch(Dispatchers.IO) {
            reportGameCrash(packageName)
        }
    }

    private suspend fun reportGameCrash(packageName: String) {
        if (!gameProcessAlive) return
        gameProcessAlive = false
        crashMonitorJob?.cancel()
        delay(400L)
        val info = captureCrashInfo(packageName) ?: return
        withContext(Dispatchers.Main) {
            crashInfo = info
            showCrashDialog = true
        }
    }

    private fun isLauncherGameProcessRunning(): Boolean {
        val expected = "$packageName:game"
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.runningAppProcesses?.any { it.processName == expected } == true) return true
        } catch (_: Exception) {
        }

        return try {
            val dirs = File("/proc").listFiles { file ->
                file.isDirectory && file.name.all { it.isDigit() }
            } ?: return true
            dirs.any { dir ->
                val cmdline = File(dir, "cmdline")
                if (!cmdline.canRead()) return@any false
                val text = cmdline.readBytes().toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
                    .trim()
                text == expected || text.startsWith("$expected ")
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun captureCrashInfo(packageName: String): CrashInfo? {
        return try {
            val bepinexLog = extractLatestErrorsFromText(readLogTail(BepInExPaths.getLogFile(packageName)))
            val capturedLogcat = listOf(
                BepInExPaths.getLogcatCaptureArchiveFile(packageName),
                BepInExPaths.getLogcatCaptureFile(packageName)
            ).joinToString("\n") { readLogTail(it) }
                .let(::extractLatestErrorsFromText)
            val javaCrash = readLogTail(BepInExPaths.getJavaCrashFile(packageName), 512 * 1024)
                .trim()
                .ifBlank { null }
            val exitRecord = ProcessExitDiagnostics.read(
                context = this,
                sinceMillis = (gameProcessStartedAtMillis - 2_000L).coerceAtLeast(0L),
                processName = "${this.packageName}:game"
            ).firstOrNull { it.isAbnormal }
            val liveLogcat = dumpGameLogcat()
            val liveErrors = extractLatestErrorsFromText(liveLogcat)
            val logcatInfo = captureLogcatCrash(liveLogcat)

            val logcatText = listOfNotNull(capturedLogcat, liveErrors, logcatInfo?.log)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString("\n\n")

            if (bepinexLog.isNullOrBlank() && logcatText.isEmpty() &&
                javaCrash == null && exitRecord == null
            ) return null

            val log = buildString {
                if (!bepinexLog.isNullOrBlank()) {
                    appendLine("--- BepInEx ---")
                    append(bepinexLog.trim())
                }
                if (logcatText.isNotEmpty()) {
                    if (isNotEmpty()) appendLine().appendLine()
                    appendLine("--- logcat ---")
                    append(logcatText)
                }
                if (javaCrash != null) {
                    if (isNotEmpty()) appendLine().appendLine()
                    appendLine("--- Java uncaught exception ---")
                    append(javaCrash)
                }
                if (exitRecord != null) {
                    if (isNotEmpty()) appendLine().appendLine()
                    appendLine("--- Android process exit ---")
                    append(exitRecord.format())
                }
            }

            val signal = exitRecord?.let { exitReasonLabel(it.reason) }
                ?: logcatInfo?.signal
                ?: javaCrash?.let { "Java exception" }
            CrashInfo(signal = signal, log = log.trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun extractLatestErrorsFromText(text: String): String? {
        if (text.isBlank()) return null
        val lines = text.lines()
        val markers = lines.indices.filter { isCrashLogLine(lines[it]) }.takeLast(4)
        if (markers.isEmpty()) return null

        // threadtime prefixes every stack frame with a timestamp, so entry-boundary
        // detection truncates AndroidRuntime and debuggerd traces. Keep bounded context
        // around several recent markers and merge overlapping windows instead.
        val selected = BooleanArray(lines.size)
        markers.forEach { marker ->
            val start = (marker - 12).coerceAtLeast(0)
            val end = (marker + 160).coerceAtMost(lines.lastIndex)
            for (index in start..end) selected[index] = true
        }
        return lines.indices
            .filter { selected[it] }
            .map { lines[it] }
            .takeLast(320)
            .joinToString("\n")
            .takeLast(32_000)
            .ifBlank { null }
    }

    private fun isCrashLogLine(line: String): Boolean {
        val lower = line.lowercase()
        if (lower.contains("fatal exception")) return true
        if (lower.contains("[error") || lower.contains("[fatal")) return true
        if (lower.contains("notsupportedexception")) return true
        if (lower.contains("il2cppinterop") &&
            (lower.contains("error") || lower.contains("exception"))
        ) {
            return true
        }
        if (lower.contains("signal") &&
            (lower.contains("sigsegv") || lower.contains("sigabrt") ||
                lower.contains("sigbus") || lower.contains("sigfpe"))
        ) {
            return true
        }
        return Regex("\\sE\\s+Unity\\b").containsMatchIn(line)
    }

    private fun readLogTail(file: File, maxBytes: Int = 256 * 1024): String {
        if (!file.isFile) return ""
        val length = file.length()
        if (length <= 0L) return ""
        return RandomAccessFile(file, "r").use { raf ->
            val start = (length - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            var text = String(bytes, Charsets.UTF_8)
            if (start > 0L) {
                val newline = text.indexOf('\n')
                if (newline >= 0) text = text.substring(newline + 1)
            }
            text
        }
    }

    private fun dumpGameLogcat(): String {
        val regular = readCommand(
            arrayOf(
                "logcat", "-d", "-v", "threadtime", "-t", "500",
                "Unity:V", "BepInEx:V", "AndroidRuntime:V", "DEBUG:V",
                "libc:V", "crash_dump64:V", "tombstoned:V", "ActivityManager:I", "*:S"
            )
        )
        val crash = readCommand(
            arrayOf("logcat", "-b", "crash", "-d", "-v", "threadtime", "-t", "500")
        )
        return listOf(regular, crash).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun readCommand(command: Array<String>): String = try {
        Runtime.getRuntime().exec(command).inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        ""
    }

    private fun findLauncherGamePid(): String? {
        val expected = "$packageName:game"
        return try {
            File("/proc").listFiles { file ->
                file.isDirectory && file.name.all { it.isDigit() }
            }?.firstOrNull { dir ->
                val cmdline = File(dir, "cmdline")
                if (!cmdline.canRead()) return@firstOrNull false
                val text = cmdline.readBytes().toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
                    .trim()
                text == expected || text.startsWith("$expected ")
            }?.name
        } catch (_: Exception) {
            null
        }
    }

    private fun captureLogcatCrash(logcat: String = dumpGameLogcat()): CrashInfo? {
        if (logcat.isBlank()) return null
        return try {
            val fatalLine = logcat.lines().firstOrNull { it.contains("FATAL EXCEPTION") }
            val signalLine = logcat.lines().firstOrNull { line ->
                line.contains("signal") && (line.contains("SIGSEGV") || line.contains("SIGABRT")
                    || line.contains("SIGBUS") || line.contains("SIGFPE"))
            }
            val unityError = logcat.lines().any { isCrashLogLine(it) }
            if (fatalLine == null && signalLine == null && !unityError) return null

            val signal = signalLine?.let { sig ->
                Regex("signal\\s+(\\d+)\\s+\\((\\w+)\\)").find(sig)?.let {
                    "${it.groupValues[2]} (${it.groupValues[1]})"
                }
            }
            val crashLog = logcat.lines().filter { line ->
                isCrashLogLine(line)
            }.takeLast(1).joinToString("\n").let {
                extractLatestErrorsFromText(logcat) ?: it
            }
            CrashInfo(signal = signal, log = crashLog)
        } catch (_: Exception) {
            null
        }
    }

    private fun exitReasonLabel(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "Java crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "Signal"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
        else -> "Abnormal exit"
    }

    private data class CrashInfo(val signal: String? = null, val log: String = "")

    private fun dismissCrashDialog() {
        showCrashDialog = false
        crashInfo = null
    }

    // Settings actions

    private fun onThemeChanged(mode: AppSettings.ThemeMode) {
        themeMode = mode
        AppSettings.setThemeMode(this, mode)
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
            val info = UpdateChecker.fetchInfo(this@MainActivity)
            withContext(Dispatchers.Main) {
                updateInfo = info
                if (info != null) {
                    val currentVersion = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                    } catch (_: Exception) { "" }

                    when {
                        !info.allowStart -> showBlocked = true
                        UpdateChecker.hasUpdate(currentVersion, info.version) -> {
                            showUpdate = true
                        }
                        info.announcementDate.isNotEmpty()
                            && info.announcementDate != AppSettings.getLastSeenAnnouncementDate(this@MainActivity) -> {
                            showAnnouncement = true
                        }
                    }
                }
            }
        }
    }

    private fun onDismissAnnouncement() {
        showAnnouncement = false
        updateInfo?.let { AppSettings.setLastSeenAnnouncementDate(this, it.announcementDate) }
    }

    private fun onDismissUpdate() {
        showUpdate = false
        updateInfo?.let {
            if (it.announcementDate.isNotEmpty()
                && it.announcementDate != AppSettings.getLastSeenAnnouncementDate(this)) {
                showAnnouncement = true
            }
        }
    }

    private fun onUpdateNow() {
        updateInfo?.let { openUpdateUrl(this, it.urlApk) }
    }

    // Log export (ported from the upstream BepInEx Android launcher)

    private fun onExportLogs() {
        scope.launch(Dispatchers.IO) {
            try {
                val extDir = getExternalFilesDir(null) ?: filesDir
                val launcherLog = File(extDir, "bepinex_launcher.log")
                val gamePackages = (
                    listOfNotNull(selectedGame?.packageName) +
                        detectedGames.map { it.packageName }
                    ).distinct()

                val exitInfoFile = File(extDir, "application_exit_info.txt")
                val exitRecords = ProcessExitDiagnostics.read(this@MainActivity)
                exitInfoFile.writeText(
                    ProcessExitDiagnostics.format(exitRecords).ifBlank {
                        "No process exit information available (requires Android 11 or newer)."
                    }
                )

                // Capture launcher process logcat
                val logcatFile = File(extDir, "logcat.txt")
                if (!logcatFile.isFile) {
                    try {
                        val pid = android.os.Process.myPid()
                        val process = Runtime.getRuntime().exec(
                            arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid")
                        )
                        val output = process.inputStream.bufferedReader().readText()
                        process.waitFor()
                        logcatFile.writeText(output)
                    } catch (e: Exception) {
                        logcatFile.writeText("Failed to capture logcat: ${e.message}")
                    }
                }

                // Capture game process logcat (Unity, BepInEx, crash)
                val gameLogcatFile = File(extDir, "game_logcat.txt")
                try {
                    val output = dumpGameLogcat()
                    gameLogcatFile.writeText(output.ifEmpty { "No game-related logs found" })
                } catch (e: Exception) {
                    gameLogcatFile.writeText("Failed to capture game logcat: ${e.message}")
                }

                // Capture crash logcat
                val crashLogcatFile = File(extDir, "crash_logcat.txt")
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-b", "crash", "-d", "-v", "threadtime"))
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    crashLogcatFile.writeText(output)
                } catch (e: Exception) {
                    crashLogcatFile.writeText("Failed to capture crash logcat: ${e.message}")
                }

                // Capture Unity log files from the game directories
                val unityLogFile = File(extDir, "unity_logs.txt")
                try {
                    val logNames = setOf(
                        "main.log", "il2cpp.log", "LogOutput.log", "bepinexlogoutput.log",
                        "BepInExLogOutput.log", "output_log.txt", "player.log"
                    )
                    val sb = StringBuilder()
                    for (pkg in gamePackages) {
                        val gameRoot = BepInExPaths.getGameRootDir(pkg)
                        if (gameRoot.exists()) {
                            gameRoot.walkTopDown()
                                .filter { it.isFile && it.length() <= 1024 * 1024 && logNames.contains(it.name) }
                                .forEach { file ->
                                    sb.appendLine("=== ${file.absolutePath} ===")
                                    try { sb.appendLine(file.readText()) } catch (_: Exception) {}
                                    sb.appendLine()
                                }
                        }
                    }
                    unityLogFile.writeText(sb.toString().ifEmpty { "No Unity log files found" })
                } catch (e: Exception) {
                    unityLogFile.writeText("Failed to capture Unity logs: ${e.message}")
                }

                // Capture tombstones
                val tombstoneFile = File(extDir, "tombstones.txt")
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("ls", "-lt", "/data/tombstones/"))
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    val sb = StringBuilder()
                    sb.appendLine(output)
                    val lines = output.lines().filter { it.contains("tombstone") }
                    for (line in lines.take(3)) {
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.isNotEmpty()) {
                            val name = parts.last()
                            try {
                                val pull = Runtime.getRuntime().exec(arrayOf("cat", "/data/tombstones/$name"))
                                val content = pull.inputStream.bufferedReader().readText()
                                pull.waitFor()
                                sb.appendLine("\n=== $name ===")
                                sb.appendLine(content)
                            } catch (_: Exception) {}
                        }
                    }
                    tombstoneFile.writeText(sb.toString())
                } catch (e: Exception) {
                    tombstoneFile.writeText("Failed to capture tombstones: ${e.message}")
                }

                // Capture BepInEx plugin logs
                val bepinexLogFile = File(extDir, "bepinex_plugin_logs.txt")
                try {
                    val sb = StringBuilder()
                    for (pkg in gamePackages) {
                        val logFile = BepInExPaths.getLogFile(pkg)
                        if (logFile.exists() && logFile.length() <= 2 * 1024 * 1024) {
                            sb.appendLine("=== ${logFile.absolutePath} ===")
                            try { sb.appendLine(logFile.readText()) } catch (_: Exception) {}
                            sb.appendLine()
                        }
                    }
                    bepinexLogFile.writeText(sb.toString().ifEmpty { "No BepInEx LogOutput.log found" })
                } catch (e: Exception) {
                    bepinexLogFile.writeText("Failed to capture BepInEx logs: ${e.message}")
                }

                // Zip all logs
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val zipFile = File(cacheDir, "nextbep_logs_$timestamp.zip")
                java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    if (launcherLog.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("bepinex_launcher.log"))
                        launcherLog.inputStream().copyTo(zos)
                        zos.closeEntry()
                    }
                    if (logcatFile.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("logcat.txt"))
                        logcatFile.inputStream().copyTo(zos)
                        zos.closeEntry()
                    }
                    if (gameLogcatFile.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("game_logcat.txt"))
                        gameLogcatFile.inputStream().copyTo(zos)
                        zos.closeEntry()
                    }
                    if (crashLogcatFile.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("crash_logcat.txt"))
                        crashLogcatFile.inputStream().copyTo(zos)
                        zos.closeEntry()
                    }
                    if (unityLogFile.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("unity_logs.txt"))
                        unityLogFile.inputStream().copyTo(zos)
                        zos.closeEntry()
                    }
                    if (tombstoneFile.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("tombstones.txt"))
                        tombstoneFile.inputStream().copyTo(zos)
                        zos.closeEntry()
                    }
                    if (bepinexLogFile.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("bepinex_plugin_logs.txt"))
                        bepinexLogFile.inputStream().copyTo(zos)
                        zos.closeEntry()
                    }
                    for (gamePackage in gamePackages) {
                        val prefix = "game-sidecar/$gamePackage"
                        val sidecars = listOf(
                            "logcat-capture.txt" to BepInExPaths.getLogcatCaptureFile(gamePackage),
                            "logcat-capture.1.txt" to BepInExPaths.getLogcatCaptureArchiveFile(gamePackage),
                            "logcat-capture-previous.txt" to BepInExPaths.getPreviousLogcatCaptureFile(gamePackage),
                            "java-crash.txt" to BepInExPaths.getJavaCrashFile(gamePackage),
                            "java-crash-previous.txt" to BepInExPaths.getPreviousJavaCrashFile(gamePackage)
                        )
                        for ((name, source) in sidecars) {
                            if (!source.isFile) continue
                            zos.putNextEntry(java.util.zip.ZipEntry("$prefix/$name"))
                            source.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                // Share via system intent
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.provider",
                        zipFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "NextBep Launcher Logs")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.crash_export_logs)))
                }
            } catch (e: Exception) {
                BepInExLog.e("Failed to export logs", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.crash_export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // UI render

    private fun setupContent() {
        setContent {
            BepInExTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
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
                    onCopyGameResources = { onCopyGameResources(it) },
                    onExportLogs = { onExportLogs() },
                    onShowAnnouncement = { showAnnouncement = true }
                )
                if (showCrashDialog) {
                    crashInfo?.let { info ->
                        CrashDialog(
                            gameName = selectedGame?.label ?: getString(R.string.app_name),
                            signal = info.signal,
                            crashLog = info.log,
                            onDismiss = { dismissCrashDialog() },
                            onExportLogs = {
                                dismissCrashDialog()
                                onExportLogs()
                            }
                        )
                    }
                }
                val currentUpdateInfo = updateInfo
                if (showBlocked && currentUpdateInfo != null) {
                    BlockedDialog(
                        message = UpdateChecker.announcementMessage(currentUpdateInfo, this@MainActivity)
                    )
                }
                if (showUpdate && currentUpdateInfo != null) {
                    val currentVersion = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                    } catch (_: Exception) { "" }
                    UpdateDialog(
                        currentVersion = currentVersion,
                        remoteVersion = currentUpdateInfo.version,
                        updateMessage = UpdateChecker.announcementMessage(currentUpdateInfo, this@MainActivity),
                        onUpdate = { onUpdateNow() },
                        onSkip = {
                            showUpdate = false
                            onDismissUpdate()
                        }
                    )
                }
                if (showAnnouncement && currentUpdateInfo != null && !showUpdate) {
                    AnnouncementDialog(
                        date = currentUpdateInfo.announcementDate,
                        message = UpdateChecker.announcementMessage(currentUpdateInfo, this@MainActivity),
                        onDismiss = { onDismissAnnouncement() }
                    )
                }
            }
        }
    }
}
