package com.bepinex.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.settings.AppSettings

/**
 * Settings screen with theme, language, and maintenance actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    packageName: String,
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    floatingLogInGame: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onFloatingLogInGameChanged: (Boolean) -> Unit,
    onClearBepInEx: () -> Unit,
    onClearDotnet: () -> Unit,
    onCopyGameResources: () -> Unit
) {
    var showThemeMenu by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back))
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
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Appearance section
            item {
                Text(
                    stringResource(R.string.settings_section_appearance),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Theme
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_theme)) },
                    supportingContent = {
                        Text(when (themeMode) {
                            AppSettings.ThemeMode.DARK -> stringResource(R.string.theme_dark)
                            AppSettings.ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                            AppSettings.ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        })
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Palette, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable { showThemeMenu = true }
                )
            }

            // Language
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_language)) },
                    supportingContent = {
                        Text(when (language) {
                            AppSettings.Language.ENGLISH -> stringResource(R.string.lang_english)
                            AppSettings.Language.CHINESE -> stringResource(R.string.lang_chinese)
                            AppSettings.Language.SYSTEM -> stringResource(R.string.lang_system)
                        })
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Language, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable { showLangMenu = true }
                )
            }

            // In-Game section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_section_ingame),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Floating Log in Game
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_floating_log)) },
                    supportingContent = { Text(stringResource(R.string.settings_floating_log_desc)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Terminal, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = {
                        Switch(
                            checked = floatingLogInGame,
                            onCheckedChange = onFloatingLogInGameChanged
                        )
                    }
                )
            }

            // Maintenance section
            item {
                Text(
                    stringResource(R.string.settings_section_maintenance),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_bepinex)) },
                    supportingContent = { Text(stringResource(R.string.settings_clear_bepinex_desc)) },
                    leadingContent = {
                        Icon(Icons.Outlined.DeleteForever, null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { confirmAction = "bepinex" }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_dotnet)) },
                    supportingContent = { Text(stringResource(R.string.settings_clear_dotnet_desc)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Delete, null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { confirmAction = "dotnet" }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_copy_resources)) },
                    supportingContent = { Text(stringResource(R.string.settings_copy_resources_desc)) },
                    leadingContent = {
                        Icon(Icons.Outlined.ContentCopy, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable { confirmAction = "copy" }
                )
            }

            // About
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_about)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Info, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier.clickable { onNavigateToAbout() }
                )
            }
        }
    }

    // Theme dropdown
    if (showThemeMenu) {
        AlertDialog(
            onDismissRequest = { showThemeMenu = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column {
                    ThemeOption(stringResource(R.string.theme_dark), AppSettings.ThemeMode.DARK, themeMode) {
                        onThemeChanged(it); showThemeMenu = false
                    }
                    ThemeOption(stringResource(R.string.theme_light), AppSettings.ThemeMode.LIGHT, themeMode) {
                        onThemeChanged(it); showThemeMenu = false
                    }
                    ThemeOption(stringResource(R.string.theme_system), AppSettings.ThemeMode.SYSTEM, themeMode) {
                        onThemeChanged(it); showThemeMenu = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeMenu = false }) { Text(stringResource(R.string.confirm_cancel)) }
            }
        )
    }

    // Language dropdown
    if (showLangMenu) {
        AlertDialog(
            onDismissRequest = { showLangMenu = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    LangOption(stringResource(R.string.lang_english), AppSettings.Language.ENGLISH, language) {
                        onLanguageChanged(it); showLangMenu = false
                    }
                    LangOption(stringResource(R.string.lang_chinese), AppSettings.Language.CHINESE, language) {
                        onLanguageChanged(it); showLangMenu = false
                    }
                    LangOption(stringResource(R.string.lang_system), AppSettings.Language.SYSTEM, language) {
                        onLanguageChanged(it); showLangMenu = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLangMenu = false }) { Text(stringResource(R.string.confirm_cancel)) }
            }
        )
    }

    // Confirmation dialogs
    confirmAction?.let { action ->
        val (title, message, onConfirm) = when (action) {
            "bepinex" -> Triple(
                stringResource(R.string.confirm_clear_bepinex_title),
                stringResource(R.string.confirm_clear_bepinex_msg),
                { onClearBepInEx(); confirmAction = null }
            )
            "dotnet" -> Triple(
                stringResource(R.string.confirm_clear_dotnet_title),
                stringResource(R.string.confirm_clear_dotnet_msg),
                { onClearDotnet(); confirmAction = null }
            )
            "copy" -> Triple(
                stringResource(R.string.confirm_copy_resources_title),
                stringResource(R.string.confirm_copy_resources_msg),
                { onCopyGameResources(); confirmAction = null }
            )
            else -> Triple("", "", { confirmAction = null })
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text(stringResource(R.string.confirm_no)) }
            }
        )
    }
}

@Composable
private fun ThemeOption(
    label: String,
    mode: AppSettings.ThemeMode,
    current: AppSettings.ThemeMode,
    onSelect: (AppSettings.ThemeMode) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = mode == current, onClick = { onSelect(mode) })
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.clickable { onSelect(mode) })
    }
}

@Composable
private fun LangOption(
    label: String,
    lang: AppSettings.Language,
    current: AppSettings.Language,
    onSelect: (AppSettings.Language) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = lang == current, onClick = { onSelect(lang) })
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.clickable { onSelect(lang) })
    }
}
