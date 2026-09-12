package com.bepinex.android.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MaintenanceAction {
    CLEAR_BEPINEX,
    CLEAR_DOTNET,
    CLEAR_LIBUNITY,
    COPY_RESOURCES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    packageName: String,
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    floatingLogInGame: Boolean,
    blockUnityKill: Boolean,
    useUnstrippedLibUnity: Boolean,
    dynamicColor: Boolean,
    animationDisabled: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onFloatingLogInGameChanged: (Boolean) -> Unit,
    onBlockUnityKillChanged: (Boolean) -> Unit,
    onUseUnstrippedLibUnityChanged: (Boolean) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationDisabledChanged: (Boolean) -> Unit,
    onClearBepInEx: () -> Unit,
    onClearDotnet: () -> Unit,
    onClearLibUnity: () -> Unit,
    onCopyGameResources: () -> Unit
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAnimationRestartDialog by remember { mutableStateOf(false) }
    var pendingAnimationValue by remember { mutableStateOf(false) }
    var maintenanceAction by remember { mutableStateOf<MaintenanceAction?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val completedMessage = stringResource(R.string.done)

    fun reportMaintenanceAction(action: MaintenanceAction) {
        when (action) {
            MaintenanceAction.CLEAR_BEPINEX -> onClearBepInEx()
            MaintenanceAction.CLEAR_DOTNET -> onClearDotnet()
            MaintenanceAction.CLEAR_LIBUNITY -> onClearLibUnity()
            MaintenanceAction.COPY_RESOURCES -> onCopyGameResources()
        }
        maintenanceAction = null
        scope.launch { snackbarHostState.showSnackbar(completedMessage) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.graphicsLayer(rotationZ = 180f)
                    )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item { SettingsSectionHeader(stringResource(R.string.settings_section_appearance)) }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_theme),
                    summary = themeLabel(themeMode),
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    onClick = { showThemeDialog = true }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_language),
                    summary = languageLabel(language),
                    icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                    onClick = { showLanguageDialog = true }
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SettingListItem(
                        title = stringResource(R.string.settings_dynamic_color),
                        summary = stringResource(R.string.settings_dynamic_color_desc),
                        icon = { Icon(Icons.Outlined.ColorLens, contentDescription = null) },
                        trailing = {
                            Switch(
                                checked = dynamicColor,
                                onCheckedChange = { checked -> onDynamicColorChanged(checked) }
                            )
                        }
                    )
                }
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_animation_disabled),
                    summary = stringResource(R.string.settings_animation_disabled_desc),
                    icon = { Icon(Icons.Outlined.Animation, contentDescription = null) },
                    trailing = {
                        Switch(
                            checked = animationDisabled,
                            onCheckedChange = { checked ->
                                pendingAnimationValue = checked
                                showAnimationRestartDialog = true
                            }
                        )
                    }
                )
            }

            item { SettingsSectionHeader(stringResource(R.string.settings_section_ingame)) }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_floating_log),
                    summary = stringResource(R.string.settings_floating_log_desc),
                    icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    trailing = {
                        Switch(
                            checked = floatingLogInGame,
                            onCheckedChange = { checked -> onFloatingLogInGameChanged(checked) }
                        )
                    }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_block_unity_kill),
                    summary = stringResource(R.string.settings_block_unity_kill_desc),
                    icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    trailing = {
                        Switch(
                            checked = blockUnityKill,
                            onCheckedChange = { checked -> onBlockUnityKillChanged(checked) }
                        )
                    }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_unstripped_libunity),
                    summary = stringResource(R.string.settings_unstripped_libunity_desc),
                    icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    trailing = {
                        Switch(
                            checked = useUnstrippedLibUnity,
                            onCheckedChange = { checked -> onUseUnstrippedLibUnityChanged(checked) }
                        )
                    }
                )
            }

            item { SettingsSectionHeader(stringResource(R.string.settings_section_maintenance)) }
            item {
                MaintenanceItem(
                    title = stringResource(R.string.settings_clear_bepinex),
                    summary = stringResource(R.string.settings_clear_bepinex_desc),
                    icon = Icons.Outlined.DeleteForever,
                    onClick = { maintenanceAction = MaintenanceAction.CLEAR_BEPINEX }
                )
            }
            item {
                MaintenanceItem(
                    title = stringResource(R.string.settings_clear_dotnet),
                    summary = stringResource(R.string.settings_clear_dotnet_desc),
                    icon = Icons.Outlined.Delete,
                    onClick = { maintenanceAction = MaintenanceAction.CLEAR_DOTNET }
                )
            }
            item {
                MaintenanceItem(
                    title = stringResource(R.string.settings_clear_libunity),
                    summary = stringResource(R.string.settings_clear_libunity_desc),
                    icon = Icons.Outlined.Delete,
                    onClick = { maintenanceAction = MaintenanceAction.CLEAR_LIBUNITY }
                )
            }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_copy_resources),
                    summary = stringResource(R.string.settings_copy_resources_desc),
                    icon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = { maintenanceAction = MaintenanceAction.COPY_RESOURCES }
                )
            }

            item { SettingsSectionHeader(stringResource(R.string.settings_about)) }
            item {
                SettingListItem(
                    title = stringResource(R.string.settings_about),
                    icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    trailing = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                    onClick = onNavigateToAbout
                )
            }
        }
    }

    if (showThemeDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_theme),
            selected = themeMode,
            options = listOf(
                AppSettings.ThemeMode.DARK to stringResource(R.string.theme_dark),
                AppSettings.ThemeMode.LIGHT to stringResource(R.string.theme_light),
                AppSettings.ThemeMode.SYSTEM to stringResource(R.string.theme_system)
            ),
            onDismiss = { showThemeDialog = false },
            onSelected = { onThemeChanged(it); showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        SelectionDialog(
            title = stringResource(R.string.settings_language),
            selected = language,
            options = listOf(
                AppSettings.Language.SYSTEM to stringResource(R.string.lang_system),
                AppSettings.Language.ENGLISH to stringResource(R.string.lang_english),
                AppSettings.Language.CHINESE to stringResource(R.string.lang_chinese),
                AppSettings.Language.CHINESE_TW to stringResource(R.string.lang_chinese_tw),
                AppSettings.Language.JAPANESE to stringResource(R.string.lang_japanese),
                AppSettings.Language.KOREAN to stringResource(R.string.lang_korean),
                AppSettings.Language.RUSSIAN to stringResource(R.string.lang_russian),
                AppSettings.Language.PORTUGUESE to stringResource(R.string.lang_portuguese),
                AppSettings.Language.PORTUGUESE_BR to stringResource(R.string.lang_portuguese_br),
                AppSettings.Language.SPANISH to stringResource(R.string.lang_spanish),
                AppSettings.Language.GERMAN to stringResource(R.string.lang_german),
                AppSettings.Language.FRENCH to stringResource(R.string.lang_french),
                AppSettings.Language.ITALIAN to stringResource(R.string.lang_italian),
                AppSettings.Language.DUTCH to stringResource(R.string.lang_dutch),
                AppSettings.Language.ARABIC to stringResource(R.string.lang_arabic),
                AppSettings.Language.ARABIC_EG to stringResource(R.string.lang_arabic_eg),
                AppSettings.Language.INDONESIAN to stringResource(R.string.lang_indonesian),
                AppSettings.Language.MALAY to stringResource(R.string.lang_malay),
                AppSettings.Language.THAI to stringResource(R.string.lang_thai),
                AppSettings.Language.VENETIAN to stringResource(R.string.lang_venetian)
            ),
            onDismiss = { showLanguageDialog = false },
            onSelected = {
                onLanguageChanged(it)
                showLanguageDialog = false
            }
        )
    }

    if (showAnimationRestartDialog) {
        AlertDialog(
            onDismissRequest = { showAnimationRestartDialog = false },
            title = { Text(stringResource(R.string.animation_restart_title)) },
            text = { Text(stringResource(R.string.animation_restart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showAnimationRestartDialog = false
                    onAnimationDisabledChanged(pendingAnimationValue)
                    scope.launch {
                        delay(300)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }) {
                    Text(stringResource(R.string.animation_restart_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAnimationRestartDialog = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }

    maintenanceAction?.let { action ->
        val dialog = maintenanceDialog(action)
        AlertDialog(
            onDismissRequest = { maintenanceAction = null },
            title = { Text(stringResource(dialog.title)) },
            text = { Text(stringResource(dialog.message)) },
            confirmButton = {
                TextButton(onClick = { reportMaintenanceAction(action) }) {
                    Text(
                        stringResource(R.string.confirm_yes),
                        color = if (dialog.destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { maintenanceAction = null }) {
                    Text(stringResource(R.string.confirm_no))
                }
            }
        )
    }
}

private data class MaintenanceDialog(
    val title: Int,
    val message: Int,
    val destructive: Boolean
)

private fun maintenanceDialog(action: MaintenanceAction) = when (action) {
    MaintenanceAction.CLEAR_BEPINEX -> MaintenanceDialog(R.string.confirm_clear_bepinex_title, R.string.confirm_clear_bepinex_msg, true)
    MaintenanceAction.CLEAR_DOTNET -> MaintenanceDialog(R.string.confirm_clear_dotnet_title, R.string.confirm_clear_dotnet_msg, true)
    MaintenanceAction.CLEAR_LIBUNITY -> MaintenanceDialog(R.string.confirm_clear_libunity_title, R.string.confirm_clear_libunity_msg, true)
    MaintenanceAction.COPY_RESOURCES -> MaintenanceDialog(R.string.confirm_copy_resources_title, R.string.confirm_copy_resources_msg, false)
}

@Composable
private fun themeLabel(mode: AppSettings.ThemeMode) = when (mode) {
    AppSettings.ThemeMode.DARK -> stringResource(R.string.theme_dark)
    AppSettings.ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    AppSettings.ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}

@Composable
private fun languageLabel(language: AppSettings.Language) = when (language) {
    AppSettings.Language.SYSTEM -> stringResource(R.string.lang_system)
    AppSettings.Language.ENGLISH -> stringResource(R.string.lang_english)
    AppSettings.Language.CHINESE -> stringResource(R.string.lang_chinese)
    AppSettings.Language.CHINESE_TW -> stringResource(R.string.lang_chinese_tw)
    AppSettings.Language.JAPANESE -> stringResource(R.string.lang_japanese)
    AppSettings.Language.KOREAN -> stringResource(R.string.lang_korean)
    AppSettings.Language.RUSSIAN -> stringResource(R.string.lang_russian)
    AppSettings.Language.PORTUGUESE -> stringResource(R.string.lang_portuguese)
    AppSettings.Language.PORTUGUESE_BR -> stringResource(R.string.lang_portuguese_br)
    AppSettings.Language.SPANISH -> stringResource(R.string.lang_spanish)
    AppSettings.Language.GERMAN -> stringResource(R.string.lang_german)
    AppSettings.Language.FRENCH -> stringResource(R.string.lang_french)
    AppSettings.Language.ITALIAN -> stringResource(R.string.lang_italian)
    AppSettings.Language.DUTCH -> stringResource(R.string.lang_dutch)
    AppSettings.Language.ARABIC -> stringResource(R.string.lang_arabic)
    AppSettings.Language.ARABIC_EG -> stringResource(R.string.lang_arabic_eg)
    AppSettings.Language.INDONESIAN -> stringResource(R.string.lang_indonesian)
    AppSettings.Language.MALAY -> stringResource(R.string.lang_malay)
    AppSettings.Language.THAI -> stringResource(R.string.lang_thai)
    AppSettings.Language.VENETIAN -> stringResource(R.string.lang_venetian)
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun SettingListItem(
    title: String,
    summary: String? = null,
    icon: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = icon,
        trailingContent = trailing,
        modifier = modifier
    )
}

@Composable
private fun MaintenanceItem(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    SettingListItem(
        title = title,
        summary = summary,
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        onClick = onClick
    )
}

@Composable
private fun <T> SelectionDialog(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelected(value) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelected(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) }
        }
    )
}
