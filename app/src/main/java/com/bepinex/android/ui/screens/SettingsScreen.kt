package com.bepinex.android.ui.screens

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings screen with appearance options and an About entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: AppSettings.ThemeMode,
    language: AppSettings.Language,
    dynamicColor: Boolean,
    animationDisabled: Boolean,
    onNavigateToAbout: () -> Unit,
    onThemeChanged: (AppSettings.ThemeMode) -> Unit,
    onLanguageChanged: (AppSettings.Language) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAnimationDisabledChanged: (Boolean) -> Unit,
    isLanguageIncompleteShown: Boolean,
    onLanguageIncompleteShown: () -> Unit
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAnimationRestartDialog by remember { mutableStateOf(false) }
    var pendingAnimationValue by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                ),
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
                        },
                        onClick = { onDynamicColorChanged(!dynamicColor) }
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
                    },
                    onClick = {
                        pendingAnimationValue = !animationDisabled
                        showAnimationRestartDialog = true
                    }
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
                AppSettings.Language.CHINESE_TW to stringResource(R.string.lang_chinese_tw)
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
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },

        leadingContent = icon,

        trailingContent = trailing,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
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
