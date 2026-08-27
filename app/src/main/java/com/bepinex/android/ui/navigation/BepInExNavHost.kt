package com.bepinex.android.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.bepinex.android.modpack.ModpackManager
import com.bepinex.android.modpack.ModpackMeta
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.components.ConfigEditorDialog
import com.bepinex.android.ui.screens.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root navigation host with bottom navigation bar.
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
    // Settings state
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    // Callbacks
    onSelectGame: (GameDetector.DetectedGame) -> Unit,
    onRescan: () -> Unit,
    onLaunch: (modpackName: String?) -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onClearBepInEx: (String) -> Unit,
    onClearDotnet: (String) -> Unit,
    onCopyGameResources: (String) -> Unit
) {
    val navController = rememberNavController()
    val modpackManager = remember { ModpackManager() }
    val context = LocalContext.current

    val logLines by BepInExLogReader.lines.collectAsState()

    // State for modpack list
    var modpacks by remember { mutableStateOf<List<ModpackMeta>>(emptyList()) }
    var activeModpackName by remember { mutableStateOf<String?>(null) }
    var modpackRefreshKey by remember { mutableStateOf(0) }

    // Load active modpack on game selection
    LaunchedEffect(selectedGame?.packageName) {
        selectedGame?.let { game ->
            activeModpackName = AppSettings.getActiveModpack(context, game.packageName)
        }
    }

    // Refresh modpack list when game changes or refresh key bumps
    LaunchedEffect(selectedGame?.packageName, modpackRefreshKey) {
        selectedGame?.let { game ->
            modpacks = modpackManager.listModpacks(game.packageName)
        }
    }

    // File picker triggers (launcher must be at composable top level)
    var importModpackTrigger by remember { mutableStateOf(false) }
    var addModTrigger by remember { mutableStateOf<String?>(null) }

    // Import modpack file picker — inline import to avoid navigation reset
    val importModpackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val game = selectedGame
            if (game != null) {
                kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                    // Must use Activity context, not applicationContext — URI permission is on the Activity
                    modpackManager.importModpack(game.packageName, uri, context)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        modpackRefreshKey++
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
                kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                    // Must use Activity context — URI permission is on the Activity
                    modpackManager.addModFromUri(context, game.packageName, targetModpack, uri)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        modpackRefreshKey++
                    }
                }
            }
        }
        addModTrigger = null
    }

    LaunchedEffect(importModpackTrigger) {
        if (importModpackTrigger) {
            importModpackLauncher.launch(arrayOf("application/zip", "*/*"))
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

    // Determine which bottom tabs to show
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in listOf(NavRoutes.GAMES, "modpacks/{packageName}", "settings/{packageName}")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    NavigationBarItem(
                        selected = currentRoute == NavRoutes.GAMES,
                        onClick = {
                            if (currentRoute != NavRoutes.GAMES) {
                                navController.navigate(NavRoutes.GAMES) {
                                    popUpTo(NavRoutes.GAMES) { inclusive = true }
                                }
                            }
                        },
                        icon = { Icon(Icons.Filled.SportsEsports, stringResource(R.string.nav_games)) },
                        label = { Text(stringResource(R.string.nav_games)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "modpacks/{packageName}",
                        onClick = {
                            selectedGame?.let { game ->
                                navController.navigate(NavRoutes.modpacks(game.packageName)) {
                                    popUpTo(NavRoutes.GAMES)
                                }
                            }
                        },
                        enabled = selectedGame != null,
                        icon = { Icon(Icons.Filled.FolderZip, stringResource(R.string.nav_modpacks)) },
                        label = { Text(stringResource(R.string.nav_modpacks)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "settings/{packageName}",
                        onClick = {
                            selectedGame?.let { game ->
                                navController.navigate(NavRoutes.settings(game.packageName)) {
                                    popUpTo(NavRoutes.GAMES)
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
    ) { innerPadding ->
        Box(
            modifier = Modifier.padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = NavRoutes.GAMES
            ) {
                // Main game screen
                composable(
                    route = NavRoutes.GAMES
                ) {
                    GameScreen(
                        detectedGames = detectedGames,
                        selectedGame = selectedGame,
                        isScanning = isScanning,
                        isFrameworkReady = isFrameworkReady,
                        isExtracting = isExtracting,
                        extractionStatus = extractionStatus,
                        activeModpackName = activeModpackName,
                        activeModpackModCount = if (activeModpackName != null)
                            modpacks.find { it.name == activeModpackName }?.modCount ?: 0 else 0,
                        onSelectGame = onSelectGame,
                        onRescan = onRescan,
                        onLaunch = { onLaunch(activeModpackName) },
                        onNavigateToSettings = {
                            selectedGame?.let {
                                navController.navigate(NavRoutes.settings(it.packageName))
                            }
                        },
                        onNavigateToModpacks = {
                            selectedGame?.let {
                                navController.navigate(NavRoutes.modpacks(it.packageName))
                            }
                        }
                    )
                }

                // Modpack list
                composable(
                    route = NavRoutes.MODPACKS,
                    arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
                    enterTransition = { slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) },
                    popExitTransition = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)) }
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    // Refresh modpacks
                    LaunchedEffect(packageName) {
                        modpacks = modpackManager.listModpacks(packageName)
                    }
                    ModpackListScreen(
                        packageName = packageName,
                        targetGameLabel = selectedGame?.label ?: packageName,
                        modpacks = modpacks,
                        activeModpackName = activeModpackName,
                        onNavigateBack = { navController.popBackStack() },
                        onCreateModpack = { name ->
                            modpackManager.createModpack(packageName, name)
                            modpacks = modpackManager.listModpacks(packageName)
                        },
                        onDeleteModpack = { name ->
                            modpackManager.deleteModpack(packageName, name)
                            if (activeModpackName == name) activeModpackName = null
                            modpacks = modpackManager.listModpacks(packageName)
                        },
                        onRenameModpack = { oldName, newName ->
                            modpackManager.renameModpack(packageName, oldName, newName)
                            modpacks = modpackManager.listModpacks(packageName)
                        },
                        onSelectModpack = { name ->
                            val previous = activeModpackName
                            if (previous != name) {
                                modpackManager.persistRuntimeState(packageName, previous)
                                if (name == null) {
                                    modpackManager.clearActiveMods(packageName)
                                } else {
                                    modpackManager.applyModpack(packageName, name)
                                }
                                AppSettings.setActiveModpack(context, packageName, name)
                                activeModpackName = name
                                modpackRefreshKey++
                            }
                        },
                        onOpenModpack = { name ->
                            navController.navigate(NavRoutes.modpackDetail(packageName, name))
                        },
                        onExportModpack = { name ->
                            val outputFile = java.io.File(
                                android.os.Environment.getExternalStorageDirectory(),
                                "BepInEx_Android/export/${name}.zip"
                            )
                            outputFile.parentFile?.mkdirs()
                            modpackManager.exportModpack(packageName, name, outputFile)
                        },
                        onImportModpack = { importModpackTrigger = true }
                    )
                }

                // Modpack detail
                composable(
                    route = NavRoutes.MODPACK_DETAIL,
                    arguments = listOf(
                        navArgument("packageName") { type = NavType.StringType },
                        navArgument("modpackName") { type = NavType.StringType }
                    ),
                    enterTransition = { slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) },
                    popExitTransition = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)) }
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    val modpackName = backStackEntry.arguments?.getString("modpackName") ?: return@composable

                    var mods by remember(packageName, modpackName, modpackRefreshKey) {
                        mutableStateOf(modpackManager.listMods(packageName, modpackName))
                    }
                    val configFiles = remember(packageName, modpackName, modpackRefreshKey) {
                        modpackManager.listConfigs(packageName, modpackName)
                    }
                    var editingConfig by remember { mutableStateOf<File?>(null) }
                    var showModLog by remember { mutableStateOf(false) }

                    ModpackDetailScreen(
                        modpackName = modpackName,
                        mods = mods,
                        configFiles = configFiles,
                        onNavigateBack = { navController.popBackStack() },
                        onAddMod = { addModTrigger = modpackName },
                        onDeleteMod = { file ->
                            modpackManager.removeMod(file)
                            mods = modpackManager.listMods(packageName, modpackName)
                        },
                        onOpenConfig = { configFile ->
                            editingConfig = configFile
                        },
                        onViewLog = { showModLog = true },
                        onExportModpack = {
                            val outputFile = java.io.File(
                                android.os.Environment.getExternalStorageDirectory(),
                                "BepInEx_Android/export/${modpackName}.zip"
                            )
                            outputFile.parentFile?.mkdirs()
                            modpackManager.exportModpack(packageName, modpackName, outputFile)
                        }
                    )

                    // Log overlay for this modpack
                    if (showModLog) {
                        val modpackLogFile = BepInExPaths.getModpackLogFile(packageName, modpackName)
                        val activeRuntimeLog = BepInExPaths.getLogFile(packageName)
                        val logFileToUse = if (activeModpackName == modpackName && activeRuntimeLog.exists()) {
                            activeRuntimeLog
                        } else {
                            modpackLogFile
                        }
                        LaunchedEffect(logFileToUse.absolutePath) {
                            BepInExLogReader.startWatchingFile(logFileToUse, scope)
                        }
                        com.bepinex.android.ui.components.LogOverlay(
                            logLines = logLines,
                            showOverlay = true,
                            onDismiss = {
                                showModLog = false
                                // Restore watching active modpack log
                                selectedGame?.let { game ->
                                    val active = AppSettings.getActiveModpack(context, game.packageName)
                                    val fallback = if (active.isNullOrEmpty()) BepInExPaths.getLogFile(game.packageName)
                                    else BepInExPaths.getModpackLogFile(game.packageName, active)
                                    BepInExLogReader.startWatchingFile(fallback, scope)
                                }
                            }
                        )
                    }

                    // Config editor
                    editingConfig?.let { file ->
                        ConfigEditorDialog(
                            configFile = file,
                            onDismiss = { editingConfig = null },
                            onSave = { f, content ->
                                f.writeText(content)
                                editingConfig = null
                            }
                        )
                    }
                }

                // Settings
                composable(
                    route = NavRoutes.SETTINGS,
                    arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
                    enterTransition = { slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) },
                    popExitTransition = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)) }
                ) { backStackEntry ->
                    val packageName = backStackEntry.arguments?.getString("packageName") ?: return@composable
                    val settingsContext = LocalContext.current
                    val floatingLogInGame = AppSettings.isFloatingLogInGameEnabled(settingsContext)
                    SettingsScreen(
                        packageName = packageName,
                        themeMode = themeMode,
                        language = language,
                        floatingLogInGame = floatingLogInGame,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) },
                        onThemeChanged = onThemeChanged,
                        onLanguageChanged = onLanguageChanged,
                        onFloatingLogInGameChanged = { enabled ->
                            AppSettings.setFloatingLogInGameEnabled(settingsContext, enabled)
                        },
                        onClearBepInEx = { onClearBepInEx(packageName) },
                        onClearDotnet = { onClearDotnet(packageName) },
                        onCopyGameResources = { onCopyGameResources(packageName) }
                    )
                }

                // About
                composable(
                    route = NavRoutes.ABOUT,
                    enterTransition = { slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) },
                    popExitTransition = { slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)) }
                ) {
                    val context = LocalContext.current
                    val versionName = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.170"
                    }.getOrDefault("0.170")
                    AboutScreen(
                        versionName = versionName,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

        }
    }
}
