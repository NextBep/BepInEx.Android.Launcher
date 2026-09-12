package com.bepinex.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bepinex.android.settings.AppSettings
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bepinex.android.GameDetector
import com.bepinex.android.R
import com.bepinex.android.modpack.ModpackMeta
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPagerScreen(
    detectedGames: List<GameDetector.DetectedGame>,
    selectedGame: GameDetector.DetectedGame?,
    isScanning: Boolean,
    isFrameworkReady: Boolean,
    isExtracting: Boolean,
    extractionStatus: String,
    activeModpackName: String?,
    activeModpackModCount: Int,
    modpacks: List<ModpackMeta>,
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    dynamicColor: Boolean,
    animationDisabled: Boolean,
    onSelectGame: (GameDetector.DetectedGame) -> Unit,
    onRescan: () -> Unit,
    onLaunch: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToGameSettings: (GameDetector.DetectedGame) -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationDisabledChanged: (Boolean) -> Unit,
    floatingLogInGame: Boolean,
    blockUnityKill: Boolean,
    onFloatingLogInGameChanged: (Boolean) -> Unit,
    onBlockUnityKillChanged: (Boolean) -> Unit,
    onClearBepInEx: () -> Unit,
    onClearDotnet: () -> Unit,
    onClearLibUnity: () -> Unit,
    onCopyGameResources: () -> Unit,
    onCreateModpack: (String) -> Unit,
    onDeleteModpack: (String) -> Unit,
    onRenameModpack: (String, String) -> Unit,
    onSelectModpack: (String?) -> Unit,
    onOpenModpack: (String) -> Unit,
    onExportModpack: (String) -> Unit,
    onImportModpack: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()

    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                    enabled = selectedGame != null,
                    onClick = {
                        if (pagerState.currentPage != 1) {
                            composeScope.launch {
                                if (animationDisabled) pagerState.scrollToPage(1)
                                else pagerState.animateScrollToPage(1)
                            }
                        }
                    },
                    icon = { Icon(Icons.Filled.FolderZip, stringResource(R.string.nav_modpacks)) },
                    label = { Text(stringResource(R.string.nav_modpacks)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    enabled = selectedGame != null,
                    onClick = {
                        if (pagerState.currentPage != 2) {
                            composeScope.launch {
                                if (animationDisabled) pagerState.scrollToPage(2)
                                else pagerState.animateScrollToPage(2)
                            }
                        }
                    },
                    icon = { Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings)) },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding()),
        ) { page ->
            when (page) {
                0 -> GameScreen(
                    detectedGames = detectedGames,
                    selectedGame = selectedGame,
                    isScanning = isScanning,
                    isFrameworkReady = isFrameworkReady,
                    isExtracting = isExtracting,
                    extractionStatus = extractionStatus,
                    activeModpackName = activeModpackName,
                    activeModpackModCount = activeModpackModCount,
                    onSelectGame = onSelectGame,
                    onRescan = onRescan,
                    onLaunch = onLaunch,
                    onNavigateToSettings = {
                        composeScope.launch {
                            if (animationDisabled) pagerState.scrollToPage(2)
                            else pagerState.animateScrollToPage(2)
                        }
                    },
                    onNavigateToModpacks = {
                        composeScope.launch {
                            if (animationDisabled) pagerState.scrollToPage(1)
                            else pagerState.animateScrollToPage(1)
                        }
                    },
                    onNavigateToGameSettings = onNavigateToGameSettings
                )
                1 -> ModpackListScreen(
                    packageName = selectedGame?.packageName ?: "",
                    targetGameLabel = selectedGame?.label ?: "",
                    modpacks = modpacks,
                    activeModpackName = activeModpackName,
                    onNavigateBack = {
                        composeScope.launch {
                            if (animationDisabled) pagerState.scrollToPage(0)
                            else pagerState.animateScrollToPage(0)
                        }
                    },
                    onCreateModpack = onCreateModpack,
                    onDeleteModpack = onDeleteModpack,
                    onRenameModpack = onRenameModpack,
                    onSelectModpack = onSelectModpack,
                    onOpenModpack = onOpenModpack,
                    onExportModpack = onExportModpack,
                    onImportModpack = onImportModpack
                )
                else -> SettingsScreen(
                    packageName = selectedGame?.packageName ?: "",
                    themeMode = themeMode,
                    language = language,
                    floatingLogInGame = floatingLogInGame,
                    blockUnityKill = blockUnityKill,
                    useUnstrippedLibUnity = AppSettings.isUseUnstrippedLibUnity(context, selectedGame?.packageName ?: ""),
                    dynamicColor = dynamicColor,
                    animationDisabled = animationDisabled,
                    onNavigateBack = {
                        composeScope.launch {
                            if (animationDisabled) pagerState.scrollToPage(0)
                            else pagerState.animateScrollToPage(0)
                        }
                    },
                    onNavigateToAbout = onNavigateToAbout,
                    onThemeChanged = onThemeChanged,
                    onLanguageChanged = onLanguageChanged,
                    onFloatingLogInGameChanged = onFloatingLogInGameChanged,
                    onBlockUnityKillChanged = onBlockUnityKillChanged,
                    onUseUnstrippedLibUnityChanged = { enabled ->
                        selectedGame?.let { game ->
                            AppSettings.setUseUnstrippedLibUnity(context, game.packageName, enabled)
                        }
                    },
                    onDynamicColorChanged = onDynamicColorChanged,
                    onAnimationDisabledChanged = onAnimationDisabledChanged,
                    onClearBepInEx = onClearBepInEx,
                    onClearDotnet = onClearDotnet,
                    onClearLibUnity = onClearLibUnity,
                    onCopyGameResources = onCopyGameResources
                )
            }
        }
    }
}
