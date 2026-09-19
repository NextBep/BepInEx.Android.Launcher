package com.bepinex.android.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bepinex.android.BepInExPaths
import com.bepinex.android.GameDetector
import com.bepinex.android.R
import com.bepinex.android.log.BepInExLogReader
import com.bepinex.android.modpack.ModpackExportProgress
import com.bepinex.android.modpack.ModpackManager
import com.bepinex.android.modpack.ModpackMeta
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.shortcut.ModpackShortcutHelper
import com.bepinex.android.ui.screens.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private fun resolveModpackFile(modpackDirectory: File, target: File): File? = runCatching {
    val canonicalRoot = modpackDirectory.canonicalFile
    val canonicalTarget = target.canonicalFile
    val isInsideModpack = canonicalTarget != canonicalRoot &&
        canonicalTarget.toPath().startsWith(canonicalRoot.toPath())

    canonicalTarget.takeIf { isInsideModpack && it.isFile }
}.getOrNull()

private fun isProtectedModpackFile(modpackDirectory: File, target: File): Boolean {
    val canonicalRoot = runCatching { modpackDirectory.canonicalFile }.getOrNull() ?: return false
    val canonicalTarget = resolveModpackFile(canonicalRoot, target) ?: return false
    return canonicalTarget.name.equals("modpack.json", ignoreCase = true) &&
        canonicalTarget.parentFile?.canonicalFile == canonicalRoot
}

private val editableTextExtensions = setOf(
    "cfg", "conf", "config", "ini", "json", "json5", "log", "lua", "txt",
    "xml", "yaml", "yml", "toml", "properties", "md", "csv", "cs", "js",
    "ts", "sh", "bat", "ps1"
)

private fun isEditableTextFile(file: File): Boolean =
    file.isFile && file.extension.lowercase() in editableTextExtensions

private fun isPluginsDll(modpackDirectory: File, target: File): Boolean {
    val canonicalRoot = runCatching { modpackDirectory.canonicalFile }.getOrNull() ?: return false
    val canonicalTarget = resolveModpackFile(canonicalRoot, target) ?: return false
    val relativeSegments = canonicalTarget.relativeTo(canonicalRoot)
        .invariantSeparatorsPath
        .split('/')

    return relativeSegments.firstOrNull()?.equals("plugins", ignoreCase = true) == true &&
        canonicalTarget.extension.equals("dll", ignoreCase = true)
}

private fun deleteModpackFile(modpackDirectory: File, target: File): Boolean {
    val safeTarget = resolveModpackFile(modpackDirectory, target) ?: return false
    if (isProtectedModpackFile(modpackDirectory, safeTarget)) return false
    return runCatching { safeTarget.delete() }.getOrDefault(false)
}

private fun NavHostController.safePopBackStack(): Boolean {
    val currentState = currentBackStackEntry?.lifecycle?.currentState
    return if (currentState == Lifecycle.State.RESUMED) popBackStack() else false
}

private data class VersionMismatchPrompt(
    val message: String,
    val onContinue: () -> Unit
)

/**
 * Root navigation host: MAIN pager (Games / Modpacks / Settings) plus detail routes.
 */
@Composable
fun BepInExNavHost(
    scope: CoroutineScope,
    // Game state
    detectedGames: List<GameDetector.DetectedGame>,
    selectedGame: GameDetector.DetectedGame?,
    isScanning: Boolean,
    isFrameworkReady: Boolean,
    isExtracting: Boolean,
    extractionStatus: String,
    extractionError: String? = null,
    // Settings state
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    dynamicColor: Boolean,
    animationDisabled: Boolean,
    // Callbacks
    onSelectGame: (GameDetector.DetectedGame) -> Unit,
    onRescan: () -> Unit,
    onLaunch: (modpackName: String?) -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationDisabledChanged: (Boolean) -> Unit,
    onClearBepInEx: (String) -> Unit,
    onClearDotnet: (String) -> Unit,
    onClearLibUnity: (String) -> Unit,
    onCopyGameResources: (String) -> Unit,
    onExportLogs: () -> Unit = {},
    onShowAnnouncement: () -> Unit = {},
    showIncompleteBanner: Boolean = false
) {
    val navController = rememberNavController()
    val modpackManager = remember { ModpackManager() }
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()

    // State for modpack list
    var modpacks by remember { mutableStateOf<List<ModpackMeta>>(emptyList()) }
    var activeModpackName by remember { mutableStateOf<String?>(null) }
    var modpackRefreshKey by remember { mutableStateOf(0) }
    var modpackIconRefreshKey by remember { mutableStateOf(0) }
    var exportProgress by remember { mutableStateOf<ModpackExportProgress?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var appliedModpackName by remember { mutableStateOf<String?>(null) }
    var isSwitchingModpack by remember { mutableStateOf(false) }
    var modpackSwitchJob by remember { mutableStateOf<Job?>(null) }

    fun enqueueModpackSwitch(packageName: String, target: String?) {
        if (target == activeModpackName && appliedModpackName == target && !isSwitchingModpack) return
        activeModpackName = target
        isSwitchingModpack = true
        if (modpackSwitchJob?.isActive == true) return
        modpackSwitchJob = composeScope.launch {
            try {
                while (isActive) {
                    val targetName = activeModpackName
                    val from = appliedModpackName
                    if (targetName == from) break
                    val ok = withContext(Dispatchers.IO) {
                        modpackManager.switchRuntime(packageName, from, targetName)
                    }
                    if (!ok) {
                        com.bepinex.android.BepInExLog.e("Failed to switch modpack to ${targetName ?: "vanilla"}")
                        activeModpackName = appliedModpackName
                        break
                    }
                    appliedModpackName = targetName
                    AppSettings.setActiveModpack(context, packageName, targetName)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                com.bepinex.android.BepInExLog.e("Modpack switch failed", error)
                activeModpackName = appliedModpackName
            } finally {
                isSwitchingModpack = false
            }
        }
    }

    fun startModpackExport(targetPackageName: String, targetModpackName: String) {
        if (exportJob?.isActive == true) return
        val outputFile = File(context.cacheDir, "$targetModpackName.${ModpackManager.MODPACK_EXTENSION}")
        outputFile.parentFile?.mkdirs()
        exportProgress = ModpackExportProgress("preparing")
        exportJob = composeScope.launch(Dispatchers.IO) {
            try {
                val success = modpackManager.exportModpack(
                    targetPackageName,
                    targetModpackName,
                    outputFile
                ) { progress ->
                    withContext(Dispatchers.Main) {
                        exportProgress = progress
                    }
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            outputFile
                        )
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = ModpackManager.MODPACK_MIME_TYPE
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, targetModpackName)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Modpack"))
                    } else {
                        android.widget.Toast.makeText(context, "Export failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                com.bepinex.android.BepInExLog.e("Export failed", error)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Export failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    exportProgress = null
                    exportJob = null
                }
            }
        }
    }

    // Refresh modpack list and validate the persisted active modpack.
    LaunchedEffect(selectedGame?.packageName, modpackRefreshKey) {
        selectedGame?.let { game ->
            val loadedModpacks = withContext(Dispatchers.IO) {
                modpackManager.listModpacks(game.packageName)
            }
            modpacks = loadedModpacks
            val savedActiveName = AppSettings.getActiveModpack(context, game.packageName)
            val validActiveName = savedActiveName?.takeIf { savedName ->
                loadedModpacks.any { it.name == savedName }
            }
            if (!isSwitchingModpack) {
                activeModpackName = validActiveName
                appliedModpackName = validActiveName
            }
            if (savedActiveName != validActiveName) {
                AppSettings.setActiveModpack(context, game.packageName, validActiveName)
            }
        }
    }

    // File picker triggers (launcher must be at composable top level)
    var importModpackTrigger by remember { mutableStateOf(false) }
    var addModTrigger by remember { mutableStateOf<String?>(null) }
    var versionMismatchPrompt by remember { mutableStateOf<VersionMismatchPrompt?>(null) }

    // Import modpack file picker — inline import to avoid navigation reset
    val importModpackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val game = selectedGame
        if (uri != null && game != null && importJob?.isActive != true) {
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        nameColumn.takeIf { it >= 0 }?.let(cursor::getString)
                    } else {
                        null
                    }
                }
            }.getOrNull()
            if (!ModpackManager.isModpackFileName(displayName)) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.modpack_invalid_file),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@rememberLauncherForActivityResult
            }
            fun runImport() {
                if (importJob?.isActive == true) return
                importJob = composeScope.launch(Dispatchers.IO) {
                    try {
                        val imported = modpackManager.importModpack(
                            game.packageName,
                            uri,
                            context,
                            displayName
                        )
                        withContext(Dispatchers.Main) {
                            if (imported != null) {
                                modpackRefreshKey++
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.modpack_invalid_archive),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (_: CancellationException) {
                    } catch (error: Exception) {
                        com.bepinex.android.BepInExLog.e("Import failed", error)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Import failed", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            importJob = null
                        }
                    }
                }
            }
            composeScope.launch(Dispatchers.IO) {
                val peeked = modpackManager.peekModpackInfo(context, uri, displayName)
                withContext(Dispatchers.Main) {
                    if (peeked != null &&
                        !ModpackManager.isGameVersionCompatible(peeked.gameVersion, game.versionName)
                    ) {
                        versionMismatchPrompt = VersionMismatchPrompt(
                            message = context.getString(
                                R.string.modpack_game_version_mismatch_message,
                                peeked.gameVersion,
                                game.versionName
                            ),
                            onContinue = { runImport() }
                        )
                    } else {
                        runImport()
                    }
                }
            }
        }
    }
    // Add mod to modpack file picker — inline import to avoid navigation reset
    val addModLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetModpack = addModTrigger
        if (uri != null && targetModpack != null) {
            val game = selectedGame
            if (game != null) {
                val displayName = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            nameColumn.takeIf { it >= 0 }?.let(cursor::getString)
                        } else {
                            null
                        }
                    }
                }.getOrNull()
                when {
                    ModpackManager.isModpackFileName(displayName) -> {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.modpack_add_mod_archive_hint),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    !ModpackManager.isModFileName(displayName) -> {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.modpack_invalid_mod_file),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        composeScope.launch(Dispatchers.IO) {
                            // Must use Activity context — URI permission is on the Activity
                            val added = modpackManager.addModFromUri(
                                context,
                                game.packageName,
                                targetModpack,
                                uri
                            )
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (added != null) {
                                    modpackRefreshKey++
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.import_failed),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }
        }
        addModTrigger = null
    }

    LaunchedEffect(importModpackTrigger) {
        if (importModpackTrigger) {
            importModpackLauncher.launch(arrayOf("application/zip", ModpackManager.MODPACK_MIME_TYPE))
            importModpackTrigger = false
        }
    }

    LaunchedEffect(addModTrigger) {
        if (addModTrigger != null) {
            addModLauncher.launch(arrayOf("*/*"))
        }
    }

    // Start watching BepInEx log when game or active modpack changes
    LaunchedEffect(selectedGame?.packageName, activeModpackName) {
        selectedGame?.let { game ->
            val active = AppSettings.getActiveModpack(context, game.packageName)
            val logFile = if (active.isNullOrEmpty()) {
                BepInExPaths.getLogFile(game.packageName)
            } else {
                BepInExPaths.getModpackLogFile(game.packageName, active)
            }
            // If active modpack log doesn't exist yet, fall back to active runtime log
            val targetLog = if (logFile.exists()) logFile else BepInExPaths.getLogFile(game.packageName)
            BepInExLogReader.startWatchingFile(targetLog, scope)
        }
    }

    DisposableEffect(selectedGame?.packageName) {
        onDispose {
            modpackSwitchJob?.cancel()
            isSwitchingModpack = false
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute == NavRoutes.MAIN

    val pagerState = rememberPagerState(pageCount = { 3 })

    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val density = LocalDensity.current
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val showPhoneBottomBar = showBottomBar && !isTablet
    val bottomBarFraction by animateFloatAsState(
        targetValue = if (showPhoneBottomBar) 1f else 0f,
        animationSpec = if (animationDisabled) snap() else spring(),
        label = "phoneBottomBar"
    )
    val animatedBottomBarPadding = with(density) {
        (bottomBarHeightPx * bottomBarFraction).toDp()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        val navContent: @Composable () -> Unit = {
        NavHost(
            navController = navController,
            startDestination = NavRoutes.MAIN,
            modifier = if (isTablet) Modifier.fillMaxSize()
                else Modifier.padding(bottom = animatedBottomBarPadding),
            enterTransition = {
                if (animationDisabled) EnterTransition.None
                else slideInHorizontally(
                    animationSpec = tween(300),
                    initialOffsetX = { it }
                ) + fadeIn(animationSpec = tween(200))
            },
            exitTransition = {
                if (animationDisabled) ExitTransition.None
                else slideOutHorizontally(
                    animationSpec = tween(300),
                    targetOffsetX = { -it }
                ) + fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                if (animationDisabled) EnterTransition.None
                else slideInHorizontally(
                    animationSpec = tween(300),
                    initialOffsetX = { -it }
                ) + fadeIn(animationSpec = tween(200))
            },
            popExitTransition = {
                if (animationDisabled) ExitTransition.None
                else slideOutHorizontally(
                    animationSpec = tween(300),
                    targetOffsetX = { it }
                ) + fadeOut(animationSpec = tween(200))
            }
        ) {
                // Main pager — Games, Modpacks, Settings
                composable(route = NavRoutes.MAIN) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 2
                    ) { page ->
                        when (page) {
                            0 -> GameScreen(
                                detectedGames = detectedGames,
                                selectedGame = selectedGame,
                                isScanning = isScanning,
                                isFrameworkReady = isFrameworkReady,
                                isExtracting = isExtracting,
                                extractionStatus = extractionStatus,
                                extractionError = extractionError,
                                activeModpackName = activeModpackName,
                                activeModpackEnabledCount = if (activeModpackName != null)
                                    modpacks.find { it.name == activeModpackName }?.enabledModCount ?: 0 else 0,
                                activeModpackModCount = if (activeModpackName != null)
                                    modpacks.find { it.name == activeModpackName }?.modCount ?: 0 else 0,
                                onSelectGame = onSelectGame,
                                onRescan = onRescan,
                                onLaunch = {
                                    if (isSwitchingModpack) {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.launch_wait_modpack_switch),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        onLaunch(activeModpackName)
                                    }
                                },
                                isSwitchingModpack = isSwitchingModpack,
                                onExportLogs = onExportLogs,
                                onShowAnnouncement = onShowAnnouncement,
                                showIncompleteBanner = showIncompleteBanner
                            )
                            1 -> {
                                val packageName = selectedGame?.packageName ?: ""

                                ModpackListScreen(
                                    packageName = packageName,
                                    targetGameLabel = selectedGame?.label ?: packageName,
                                    gameVersion = selectedGame?.versionName.orEmpty(),
                                    modpacks = modpacks,
                                    activeModpackName = activeModpackName,
                                    isSwitching = isSwitchingModpack,
                                    iconRefreshKey = modpackIconRefreshKey,
                                    onCreateModpack = { name, createShortcut, iconBitmap, gameVersion ->
                                        val created = modpackManager.createModpack(
                                            packageName,
                                            name,
                                            gameVersion
                                        )
                                        if (created != null) {
                                            modpackManager.updateMeta(packageName, created.name, createShortcut)
                                            if (iconBitmap != null) {
                                                modpackManager.saveModpackIcon(packageName, created.name, iconBitmap, "png")
                                            }
                                            if (createShortcut) {
                                                ModpackShortcutHelper.createShortcut(context, packageName, created.name, created.name)
                                            }
                                        }
                                        modpackRefreshKey++
                                    },
                                    onDeleteModpack = { name ->
                                        composeScope.launch {
                                            modpackSwitchJob?.join()
                                            val wasActive = activeModpackName == name || appliedModpackName == name
                                            val deleted = withContext(Dispatchers.IO) {
                                                if (wasActive) {
                                                    modpackManager.switchRuntime(packageName, name, null)
                                                }
                                                modpackManager.deleteModpack(packageName, name)
                                            }
                                            if (deleted) {
                                                modpacks = modpacks.filterNot { it.name == name }
                                                if (wasActive) {
                                                    AppSettings.setActiveModpack(context, packageName, null)
                                                    activeModpackName = null
                                                    appliedModpackName = null
                                                }
                                                modpackRefreshKey++
                                            }
                                        }
                                    },
                                    onEditModpack = { oldName, newName, createShortcut, iconBitmap, gameVersion ->
                                        val existingShortcut = modpackManager.listModpacks(packageName)
                                            .firstOrNull { it.name == oldName }
                                            ?.createShortcut == true
                                        val normalizedName = modpackManager.normalizeModpackName(newName)
                                        val success = modpackManager.renameModpack(packageName, oldName, newName)
                                        if (success) {
                                            val shortcutShouldExist = createShortcut || existingShortcut
                                            if (activeModpackName == oldName) {
                                                activeModpackName = normalizedName
                                                AppSettings.setActiveModpack(context, packageName, normalizedName)
                                            }
                                            modpackManager.updateMeta(
                                                packageName,
                                                normalizedName,
                                                shortcutShouldExist,
                                                gameVersion
                                            )
                                            if (createShortcut || (existingShortcut && oldName != normalizedName)) {
                                                if (oldName != normalizedName) {
                                                    ModpackShortcutHelper.removeShortcut(context, packageName, oldName)
                                                }
                                                ModpackShortcutHelper.createShortcut(context, packageName, normalizedName, newName)
                                            }
                                            if (iconBitmap != null) {
                                                val ext = when {
                                                    iconBitmap.config == android.graphics.Bitmap.Config.RGB_565 -> "jpg"
                                                    else -> "png"
                                                }
                                                modpackManager.saveModpackIcon(packageName, normalizedName, iconBitmap, ext)
                                            }
                                            modpacks = modpackManager.listModpacks(packageName)
                                            modpackRefreshKey++
                                            modpackIconRefreshKey++
                                        }
                                        success
                                    },
                                    onSelectModpack = { name ->
                                        enqueueModpackSwitch(packageName, name)
                                    },
                                    onOpenModpack = { name ->
                                        navController.navigate(NavRoutes.modpackDetail(packageName, name))
                                    },
                                    onExportModpack = { name ->
                                        startModpackExport(packageName, name)
                                    },
                                    onImportModpack = { importModpackTrigger = true },
                                    onRefresh = {
                                        modpackRefreshKey++
                                        modpackIconRefreshKey++
                                    }
                                )
                            }
                            2 -> {
                                val packageName = selectedGame?.packageName ?: ""
                                val settingsContext = LocalContext.current
                                var floatingLogInGame by remember {
                                    mutableStateOf(AppSettings.isFloatingLogInGameEnabled(settingsContext))
                                }
                                var useUnstrippedLibUnity by remember {
                                    mutableStateOf(AppSettings.isUseUnstrippedLibUnity(settingsContext))
                                }
                                var dynamicColorSetting by remember {
                                    mutableStateOf(AppSettings.isDynamicColorEnabled(settingsContext))
                                }
                                var animationDisabledSetting by remember {
                                    mutableStateOf(AppSettings.isAnimationDisabled(settingsContext))
                                }
                                SettingsScreen(
                                    themeMode = themeMode,
                                    language = language,
                                    dynamicColor = dynamicColorSetting,
                                    animationDisabled = animationDisabledSetting,
                                    floatingLogInGame = floatingLogInGame,
                                    useUnstrippedLibUnity = useUnstrippedLibUnity,
                                    onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) },
                                    onNavigateToGameSettings = {
                                        selectedGame?.let { game ->
                                            navController.navigate(
                                                NavRoutes.gameSettings(game.packageName, game.label)
                                            )
                                        }
                                    },
                                    onThemeChanged = onThemeChanged,
                                    onLanguageChanged = onLanguageChanged,
                                    onDynamicColorChanged = { enabled ->
                                        AppSettings.setDynamicColorEnabled(settingsContext, enabled)
                                        dynamicColorSetting = enabled
                                        onDynamicColorChanged(enabled)
                                    },
                                    onAnimationDisabledChanged = { disabled ->
                                        AppSettings.setAnimationDisabled(settingsContext, disabled)
                                        animationDisabledSetting = disabled
                                    },
                                    onFloatingLogInGameChanged = { enabled ->
                                        AppSettings.setFloatingLogInGameEnabled(settingsContext, enabled)
                                        floatingLogInGame = enabled
                                    },
                                    onUseUnstrippedLibUnityChanged = { enabled ->
                                        AppSettings.setUseUnstrippedLibUnity(settingsContext, enabled)
                                        useUnstrippedLibUnity = enabled
                                    },
                                    onClearBepInEx = { onClearBepInEx(packageName) },
                                    onClearDotnet = { onClearDotnet(packageName) },
                                    onClearLibUnity = { onClearLibUnity(packageName) },
                                    onCopyGameResources = { onCopyGameResources(packageName) },
                                    isLanguageIncompleteShown = AppSettings.isLanguageIncompleteShown(settingsContext),
                                    onLanguageIncompleteShown = { AppSettings.setLanguageIncompleteShown(settingsContext, true) }
                                )
                            }
                        }
                    }
                }

                // Modpack detail
                composable(
                    route = NavRoutes.MODPACK_DETAIL,
                    arguments = listOf(
                        navArgument("packageName") { type = NavType.StringType },
                        navArgument("modpackName") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    val modpackName = backStackEntry.arguments?.getString("modpackName") ?: return@composable

                    var mods by remember(packageName, modpackName, modpackRefreshKey) {
                        mutableStateOf(modpackManager.listModEntries(packageName, modpackName))
                    }
                    val configFiles = remember(packageName, modpackName, modpackRefreshKey) {
                        modpackManager.listConfigs(packageName, modpackName)
                    }

                    ModpackDetailScreen(
                        packageName = packageName,
                        modpackName = modpackName,
                        mods = mods,
                        configFiles = configFiles,
                        onNavigateBack = { navController.safePopBackStack() },
                        onAddMod = { addModTrigger = modpackName },
                        onDeleteMod = { mod ->
                            modpackManager.removeMod(mod.file)
                            mods = modpackManager.listModEntries(packageName, modpackName)
                            modpackRefreshKey++
                        },
                        onRenameMod = { mod, displayName ->
                            modpackManager.setDllDisplayName(
                                packageName,
                                modpackName,
                                mod.relativePath,
                                displayName
                            )
                            mods = modpackManager.listModEntries(packageName, modpackName)
                        },
                        onSetModCategory = { mod, category ->
                            modpackManager.setDllCategory(
                                packageName,
                                modpackName,
                                mod.relativePath,
                                category
                            )
                            mods = modpackManager.listModEntries(packageName, modpackName)
                        },
                        onToggleMod = { mod, enabled ->
                            modpackManager.setDllEnabled(
                                packageName,
                                modpackName,
                                mod.relativePath,
                                enabled
                            )
                            mods = modpackManager.listModEntries(packageName, modpackName)
                            modpackRefreshKey++
                        },
                        onOpenConfig = { configFile ->
                            navController.navigate(NavRoutes.configEditor(configFile.absolutePath))
                        },
                        onViewLog = {
                            navController.navigate(NavRoutes.logViewer(packageName, modpackName))
                        },
                        onBrowseModFiles = {
                            navController.navigate(NavRoutes.modFileBrowser(packageName, modpackName))
                        },
                        onExportModpack = {
                            startModpackExport(packageName, modpackName)
                        },
                        onImportDownloadDlls = { files ->
                            composeScope.launch(Dispatchers.IO) {
                                var importedCount = 0
                                try {
                                    files.forEach { file ->
                                        val added = modpackManager.addMod(
                                            packageName,
                                            modpackName,
                                            file
                                        )
                                        if (added != null) importedCount++
                                    }
                                    withContext(Dispatchers.Main) {
                                        mods = modpackManager.listModEntries(
                                            packageName,
                                            modpackName
                                        )
                                        modpackRefreshKey++
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.modpack_scan_dlls_imported,
                                                importedCount,
                                                files.size
                                            ),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (_: CancellationException) {
                                } catch (error: Exception) {
                                    com.bepinex.android.BepInExLog.e("DLL download import failed", error)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.import_failed),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },
                        onImportModsFromModpack = { sourceModpack, relativePaths ->
                            composeScope.launch(Dispatchers.IO) {
                                try {
                                    val result = modpackManager.importModsFromModpack(
                                        packageName,
                                        sourceModpack,
                                        modpackName,
                                        relativePaths
                                    )
                                    withContext(Dispatchers.Main) {
                                        mods = modpackManager.listModEntries(
                                            packageName,
                                            modpackName
                                        )
                                        modpackRefreshKey++
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.modpack_import_from_other_imported,
                                                result.importedMods,
                                                relativePaths.size,
                                                result.importedConfigs
                                            ),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (_: CancellationException) {
                                } catch (error: Exception) {
                                    com.bepinex.android.BepInExLog.e("Import from modpack failed", error)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.import_failed),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    )

                }

                // Modpack file browser
                composable(
                    route = NavRoutes.MOD_FILE_BROWSER,
                    arguments = listOf(
                        navArgument("packageName") { type = NavType.StringType },
                        navArgument("modpackName") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName")
                        ?: return@composable
                    val modpackName = backStackEntry.arguments?.getString("modpackName")
                        ?: return@composable
                    val modpackDirectory = BepInExPaths.getModpackDir(packageName, modpackName)

                    ModFileBrowserScreen(
                        rootDirectory = modpackDirectory,
                        onNavigateBack = { navController.safePopBackStack() },
                        onFileClick = { file ->
                            val safeFile = resolveModpackFile(modpackDirectory, file)
                            if (safeFile != null &&
                                !isProtectedModpackFile(modpackDirectory, safeFile) &&
                                isEditableTextFile(safeFile)
                            ) {
                                navController.navigate(NavRoutes.configEditor(safeFile.absolutePath)) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        onDeleteFile = { file ->
                            val deletedPluginDll = isPluginsDll(modpackDirectory, file)
                            deleteModpackFile(modpackDirectory, file).also { deleted ->
                                if (deleted && deletedPluginDll) {
                                    modpackRefreshKey++
                                }
                            }
                        }
                    )
                }

                // Log Viewer
                composable(
                    route = NavRoutes.LOG_VIEWER,
                    arguments = listOf(
                        navArgument("packageName") { type = NavType.StringType },
                        navArgument("modpackName") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val pkg = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    val mpName = backStackEntry.arguments?.getString("modpackName") ?: return@composable
                    val logFile = com.bepinex.android.BepInExPaths.getModpackLogFile(pkg, mpName)

                    val autoScroll = AppSettings.isLogAutoScrollEnabled(context)
                    val logWordWrap = AppSettings.isLogWordWrapEnabled(context)
                    val logLineNumbers = AppSettings.isLogLineNumbersEnabled(context)

                    LogViewerScreen(
                        logFilePath = logFile.absolutePath,
                        onNavigateBack = { navController.safePopBackStack() },
                        onSettingsClick = { navController.navigate(NavRoutes.LOG_VIEWER_SETTINGS) },
                        autoScroll = autoScroll,
                        wordWrap = logWordWrap,
                        showLineNumbers = logLineNumbers,
                        onAutoScrollChange = { AppSettings.setLogAutoScrollEnabled(context, it) },
                        onWordWrapChange = { AppSettings.setLogWordWrapEnabled(context, it) },
                        onLineNumbersChange = { AppSettings.setLogLineNumbersEnabled(context, it) }
                    )
                }

                // Log Viewer Settings
                composable(route = NavRoutes.LOG_VIEWER_SETTINGS) {
                    var autoScroll by remember { mutableStateOf(AppSettings.isLogAutoScrollEnabled(context)) }
                    var logWordWrap by remember { mutableStateOf(AppSettings.isLogWordWrapEnabled(context)) }
                    var logLineNumbers by remember { mutableStateOf(AppSettings.isLogLineNumbersEnabled(context)) }

                    LogViewerSettingsScreen(
                        onNavigateBack = { navController.safePopBackStack() },
                        autoScroll = autoScroll,
                        wordWrap = logWordWrap,
                        showLineNumbers = logLineNumbers,
                        onAutoScrollChange = {
                            autoScroll = it
                            AppSettings.setLogAutoScrollEnabled(context, it)
                        },
                        onWordWrapChange = {
                            logWordWrap = it
                            AppSettings.setLogWordWrapEnabled(context, it)
                        },
                        onLineNumbersChange = {
                            logLineNumbers = it
                            AppSettings.setLogLineNumbersEnabled(context, it)
                        }
                    )
                }

                // Config Editor
                composable(
                    route = NavRoutes.CONFIG_EDITOR,
                    arguments = listOf(navArgument("filePath") { type = NavType.StringType })
                ) { backStackEntry ->
                    val encodedPath = backStackEntry.arguments?.getString("filePath") ?: return@composable
                    val filePath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
                    val file = File(filePath)

                    val wordWrap = AppSettings.isViewerWordWrapEnabled(context)
                    val showLineNumbers = AppSettings.isViewerLineNumbersEnabled(context)

                    TextViewerScreen(
                        file = file,
                        onNavigateBack = { navController.safePopBackStack() },
                        onSave = { f, content ->
                            val success = runCatching {
                                f.writeText(content)
                            }.isSuccess
                            success.also {
                                if (success) navController.safePopBackStack()
                            }
                        },
                        onSettingsClick = { navController.navigate(NavRoutes.VIEWER_SETTINGS) },
                        wordWrap = wordWrap,
                        showLineNumbers = showLineNumbers
                    )
                }

                // Viewer Settings
                composable(route = NavRoutes.VIEWER_SETTINGS) {
                    var wordWrap by remember { mutableStateOf(AppSettings.isViewerWordWrapEnabled(context)) }
                    var showLineNumbers by remember { mutableStateOf(AppSettings.isViewerLineNumbersEnabled(context)) }

                    ViewerSettingsScreen(
                        onNavigateBack = { navController.safePopBackStack() },
                        wordWrap = wordWrap,
                        showLineNumbers = showLineNumbers,
                        onWordWrapChange = {
                            wordWrap = it
                            AppSettings.setViewerWordWrapEnabled(context, it)
                        },
                        onLineNumbersChange = {
                            showLineNumbers = it
                            AppSettings.setViewerLineNumbersEnabled(context, it)
                        }
                    )
                }

                // Per-game settings (B-specific)
                composable(
                    route = NavRoutes.GAME_SETTINGS,
                    arguments = listOf(
                        navArgument("packageName") { type = NavType.StringType },
                        navArgument("gameLabel") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val pkg = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    val label = java.net.URLDecoder.decode(
                        backStackEntry.arguments?.getString("gameLabel") ?: "", "UTF-8"
                    )
                    val gsContext = LocalContext.current
                    var gsFloatingLog by remember {
                        mutableStateOf(AppSettings.isFloatingLogInGameEnabled(gsContext))
                    }
                    var gsBlockKill by remember(pkg) {
                        mutableStateOf(AppSettings.isUnityKillBlockEnabled(gsContext, pkg))
                    }
                    var gsUnstripped by remember(pkg) {
                        mutableStateOf(AppSettings.isUseUnstrippedLibUnity(gsContext, pkg))
                    }
                    GameSettingsScreen(
                        packageName = pkg,
                        gameLabel = label,
                        floatingLogInGame = gsFloatingLog,
                        blockUnityKill = gsBlockKill,
                        useUnstrippedLibUnity = gsUnstripped,
                        onNavigateBack = { navController.safePopBackStack() },
                        onFloatingLogInGameChanged = { enabled ->
                            AppSettings.setFloatingLogInGameEnabled(gsContext, enabled)
                            gsFloatingLog = enabled
                        },
                        onBlockUnityKillChanged = { enabled ->
                            AppSettings.setUnityKillBlockEnabled(gsContext, pkg, enabled)
                            gsBlockKill = enabled
                        },
                        onUseUnstrippedLibUnityChanged = { enabled ->
                            AppSettings.setUseUnstrippedLibUnity(gsContext, pkg, enabled)
                            gsUnstripped = enabled
                        },
                        onClearBepInEx = { onClearBepInEx(pkg) },
                        onClearDotnet = { onClearDotnet(pkg) },
                        onClearLibUnity = { onClearLibUnity(pkg) },
                        onCopyGameResources = { onCopyGameResources(pkg) }
                    )
                }

                // About
                composable(route = NavRoutes.ABOUT) {
                    val aboutContext = LocalContext.current
                    val versionName = runCatching {
                        aboutContext.packageManager.getPackageInfo(aboutContext.packageName, 0).versionName ?: "1.0"
                    }.getOrDefault("1.0")
                    AboutScreen(
                        versionName = versionName,
                        onNavigateBack = { navController.safePopBackStack() },
                        onNavigateToCredits = { navController.navigate(NavRoutes.CREDITS) }
                    )
                }

                // Credits
                composable(route = NavRoutes.CREDITS) {
                    CreditsScreen(onNavigateBack = { navController.safePopBackStack() })
                }
            }
                exportProgress?.let { progress ->
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.modpack_export)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (progress.phase == "preparing") {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Text(stringResource(R.string.modpack_export_preparing))
                                } else {
                                    LinearProgressIndicator(
                                        progress = { progress.fraction },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        stringResource(
                                            R.string.modpack_export_progress,
                                            progress.completedFiles,
                                            progress.totalFiles
                                        )
                                    )
                                    progress.currentFile?.let {
                                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { exportJob?.cancel() }) {
                                Text(stringResource(R.string.modpack_export_cancel))
                            }
                        }
                    )
                }
                if (importJob?.isActive == true) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.modpack_import)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Text(stringResource(R.string.modpack_importing))
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { importJob?.cancel() }) {
                                Text(stringResource(R.string.modpack_import_cancel))
                            }
                        }
                    )
                }
                versionMismatchPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { versionMismatchPrompt = null },
                        title = { Text(stringResource(R.string.modpack_game_version_mismatch_title)) },
                        text = { Text(prompt.message) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    versionMismatchPrompt = null
                                    prompt.onContinue()
                                }
                            ) {
                                Text(stringResource(R.string.modpack_game_version_mismatch_continue))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { versionMismatchPrompt = null }) {
                                Text(stringResource(R.string.modpack_import_cancel))
                            }
                        }
                    )
                }
        } // navContent

        if (isTablet && showBottomBar) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    NavigationRailItem(
                        selected = pagerState.currentPage == 0,
                        onClick = { composeScope.launch { if (animationDisabled) pagerState.scrollToPage(0) else pagerState.animateScrollToPage(0) } },
                        icon = { Icon(Icons.Filled.SportsEsports, stringResource(R.string.nav_games)) },
                        label = { Text(stringResource(R.string.nav_games)) }
                    )
                    NavigationRailItem(
                        selected = pagerState.currentPage == 1,
                        onClick = { composeScope.launch { if (animationDisabled) pagerState.scrollToPage(1) else pagerState.animateScrollToPage(1) } },
                        enabled = selectedGame != null,
                        icon = { Icon(Icons.Filled.FolderZip, stringResource(R.string.nav_modpacks)) },
                        label = { Text(stringResource(R.string.nav_modpacks)) }
                    )
                    NavigationRailItem(
                        selected = pagerState.currentPage == 2,
                        onClick = { composeScope.launch { if (animationDisabled) pagerState.scrollToPage(2) else pagerState.animateScrollToPage(2) } },
                        enabled = selectedGame != null,
                        icon = { Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings)) },
                        label = { Text(stringResource(R.string.nav_settings)) }
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    navContent()
                }
            }
        } else {
            navContent()
        }

    }
    if (!isTablet) {
        NavigationBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bottomBarHeightPx = it.height }
                .offset {
                    IntOffset(
                        x = 0,
                        y = ((1f - bottomBarFraction) * bottomBarHeightPx).roundToInt()
                    )
                },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            NavigationBarItem(
                selected = pagerState.currentPage == 0,
                onClick = {
                    if (pagerState.currentPage != 0) {
                        composeScope.launch {
                            if (animationDisabled) pagerState.scrollToPage(0)
                            else pagerState.animateScrollToPage(0)
                        }
                    }
                },
                icon = { Icon(Icons.Filled.SportsEsports, stringResource(R.string.nav_games)) },
                label = { Text(stringResource(R.string.nav_games)) }
            )
            NavigationBarItem(
                selected = pagerState.currentPage == 1,
                onClick = {
                    if (selectedGame != null && pagerState.currentPage != 1) {
                        composeScope.launch {
                            if (animationDisabled) pagerState.scrollToPage(1)
                            else pagerState.animateScrollToPage(1)
                        }
                    }
                },
                enabled = selectedGame != null,
                icon = { Icon(Icons.Filled.FolderZip, stringResource(R.string.nav_modpacks)) },
                label = { Text(stringResource(R.string.nav_modpacks)) }
            )
            NavigationBarItem(
                selected = pagerState.currentPage == 2,
                onClick = {
                    if (selectedGame != null && pagerState.currentPage != 2) {
                        composeScope.launch {
                            if (animationDisabled) pagerState.scrollToPage(2)
                            else pagerState.animateScrollToPage(2)
                        }
                    }
                },
                enabled = selectedGame != null,
                icon = { Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings)) },
                label = { Text(stringResource(R.string.nav_settings)) }
            )
        }
    }
    }
}
