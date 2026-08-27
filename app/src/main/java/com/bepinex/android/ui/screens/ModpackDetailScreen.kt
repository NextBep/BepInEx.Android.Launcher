package com.bepinex.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import java.io.File

/**
 * Manage mods within a specific modpack and edit config files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModpackDetailScreen(
    modpackName: String,
    mods: List<File>,
    configFiles: List<File>,
    onNavigateBack: () -> Unit,
    onAddMod: () -> Unit,
    onDeleteMod: (File) -> Unit,
    onOpenConfig: (File) -> Unit,
    onViewLog: () -> Unit,
    onExportModpack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(modpackName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onViewLog) {
                        Icon(Icons.Filled.Terminal, stringResource(R.string.log_title))
                    }
                    IconButton(onClick = onExportModpack) {
                        Icon(Icons.Filled.Share, stringResource(R.string.modpack_export))
                    }
                    IconButton(onClick = onAddMod) {
                        Icon(Icons.Filled.Add, stringResource(R.string.modpack_add_mod))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Mods section
            item {
                Text(
                    stringResource(R.string.installed_mods),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (mods.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.modpack_no_mods),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(mods, key = { it.absolutePath }) { mod ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                Modifier.size(38.dp), shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Extension, null, Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(mod.name, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatFileSize(mod.length()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onDeleteMod(mod) }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // Config files section
            if (configFiles.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_section_config),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(configFiles, key = { it.absolutePath }) { cfg ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        onClick = { onOpenConfig(cfg) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Settings, null, Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(cfg.name, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ChevronRight, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
