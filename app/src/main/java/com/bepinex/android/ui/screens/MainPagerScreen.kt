package com.bepinex.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPagerScreen(
    scope: CoroutineScope,
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
    val pageScope = scope
    val context = LocalContext.current

    LaunchedEffect(pagerState.currentPage) {
        // Page state is intentionally kept in the pager; deep routes are
        // handled by the parent navigation host.
    }

    androidx.compose.material3.Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { pageScope.launch { selectPage(pagerState, 0, animationDisabled) } },
                    icon = { Icon(Icons.Filled.SportsEsports, stringResource(R.string.nav_games)) },
                    label = { Text(stringResource(R.string.nav_games)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    enabled = selectedGame != null,
                    onClick = { pageScope.launch { selectPage(pagerState, 1, animationDisabled) } },
                    icon = { Icon(Icons.Filled.FolderZip, stringResource(R.string.nav_modpacks)) },
                    label = { Text(stringResource(R.string.nav_modpacks)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    enabled = selectedGame != null,
                    onClick = { pageScope.launch { selectPage(pagerState, 2, animationDisabled) } },
                    icon = { Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings)) },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues
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
                        pageScope.launch { selectPage(pagerState, 2, animationDisabled) }
                    },
                    onNavigateToModpacks = {
                        pageScope.launch { selectPage(pagerState, 1, animationDisabled) }
                    }
                )
                1 -> ModpackListScreen(
                    packageName = selectedGame?.packageName ?: "",
                    targetGameLabel = selectedGame?.label ?: "",
                    modpacks = modpacks,
                    activeModpackName = activeModpackName,
                    onNavigateBack = {
                        pageScope.launch { selectPage(pagerState, 0, animationDisabled) }
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
                        pageScope.launch { selectPage(pagerState, 0, animationDisabled) }
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

@OptIn(ExperimentalFoundationApi::class)
private suspend fun selectPage(state: PagerState, page: Int, disabled: Boolean) {
    if (disabled) state.scrollToPage(page) else state.animateScrollToPage(page)
}
