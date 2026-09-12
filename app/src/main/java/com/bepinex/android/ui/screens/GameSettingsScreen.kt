package com.bepinex.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.settings.AppSettings

/**
 * Per-game settings screen — inspired by FusionCore's GameSettingsActivity.
 * Each game gets its own dedicated settings page with toggles and maintenance actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsScreen(
    packageName: String,
    gameLabel: String,
    floatingLogInGame: Boolean,
    blockUnityKill: Boolean,
    useUnstrippedLibUnity: Boolean,
    onNavigateBack: () -> Unit,
    onFloatingLogInGameChanged: (Boolean) -> Unit,
    onBlockUnityKillChanged: (Boolean) -> Unit,
    onUseUnstrippedLibUnityChanged: (Boolean) -> Unit,
    onClearBepInEx: () -> Unit,
    onClearDotnet: () -> Unit,
    onClearLibUnity: () -> Unit,
    onCopyGameResources: () -> Unit
) {
    var confirmAction by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(gameLabel) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
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
            // Game info card
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(gameLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // In-Game section
            item {
                Text(
                    stringResource(R.string.settings_section_ingame),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Floating Log
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_floating_log)) },
                    supportingContent = { Text(stringResource(R.string.settings_floating_log_desc)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Terminal, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = {
                        Switch(checked = floatingLogInGame, onCheckedChange = onFloatingLogInGameChanged)
                    }
                )
            }

            // Block Unity Kill
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_block_unity_kill)) },
                    supportingContent = { Text(stringResource(R.string.settings_block_unity_kill_desc)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Shield, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = {
                        Switch(checked = blockUnityKill, onCheckedChange = onBlockUnityKillChanged)
                    }
                )
            }

            // Unstripped libunity
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_unstripped_libunity)) },
                    supportingContent = { Text(stringResource(R.string.settings_unstripped_libunity_desc)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Code, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = {
                        Switch(checked = useUnstrippedLibUnity, onCheckedChange = onUseUnstrippedLibUnityChanged)
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
                    headlineContent = { Text(stringResource(R.string.settings_clear_libunity)) },
                    supportingContent = { Text(stringResource(R.string.settings_clear_libunity_desc)) },
                    leadingContent = {
                        Icon(Icons.Outlined.Delete, null,
                            tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { confirmAction = "libunity" }
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
        }
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
            "libunity" -> Triple(
                stringResource(R.string.confirm_clear_libunity_title),
                stringResource(R.string.confirm_clear_libunity_msg),
                { onClearLibUnity(); confirmAction = null }
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
