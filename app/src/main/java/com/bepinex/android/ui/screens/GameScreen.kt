package com.bepinex.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import com.bepinex.android.BepInExLog
import com.bepinex.android.GameDetector
import com.bepinex.android.R
import java.io.File

/**
 * Main game selection and mod management screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    detectedGames: List<GameDetector.DetectedGame>,
    selectedGame: GameDetector.DetectedGame?,
    isScanning: Boolean,
    isFrameworkReady: Boolean,
    isExtracting: Boolean,
    extractionStatus: String,
    activeModpackName: String?,
    activeModpackModCount: Int,
    onSelectGame: (GameDetector.DetectedGame) -> Unit,
    onRescan: () -> Unit,
    onLaunch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToModpacks: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BepInEx", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Launcher",
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (selectedGame != null) {
                        IconButton(onClick = onNavigateToModpacks) {
                            Icon(Icons.Filled.FolderZip, stringResource(R.string.nav_modpacks),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onRescan) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.rescan),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Game list section
            item {
                Text(
                    stringResource(R.string.scan_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (isScanning) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.scanning),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else if (detectedGames.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Outlined.SearchOff, null,
                                Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.no_games_found),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.no_games_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onRescan) {
                                Text(stringResource(R.string.scan_again))
                            }
                        }
                    }
                }
            } else {
                items(detectedGames, key = { it.packageName }) { game ->
                    val isSelected = game.packageName == selectedGame?.packageName
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.clickable { onSelectGame(game) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GameIcon(game.icon, Modifier.size(48.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(game.label, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(game.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("v${game.versionName} 路 ${game.unityVersion ?: "Unity"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isSelected) {
                                Icon(Icons.Filled.CheckCircle, "Selected",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // Selected game card
            if (selectedGame != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GameIcon(selectedGame.icon, Modifier.size(56.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(selectedGame.label,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(selectedGame.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(2.dp))
                                    Text("v${selectedGame.versionName} 路 IL2CPP 路 arm64-v8a",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            // Status row
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isFrameworkReady)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val dotColor = if (isFrameworkReady)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.tertiary
                                        Surface(
                                            Modifier.size(6.dp), shape = RoundedCornerShape(3.dp),
                                            color = dotColor
                                        ) {}
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (isFrameworkReady) stringResource(R.string.framework_ready)
                                            else stringResource(R.string.framework_setting_up),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                            // Active modpack info
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (activeModpackName != null)
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (activeModpackName != null) Icons.Filled.FolderZip else Icons.Outlined.Block,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = if (activeModpackName != null)
                                                MaterialTheme.colorScheme.secondary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (activeModpackName != null)
                                                "$activeModpackName 路 $activeModpackModCount mod(s)"
                                            else
                                                stringResource(R.string.modpack_vanilla),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }

                            // Extraction progress
                            AnimatedVisibility(isExtracting) {
                                Column {
                                    Spacer(Modifier.height(12.dp))
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(4.dp))
                                    Text(extractionStatus, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // Quick actions
                item {
                    Button(
                        onClick = onLaunch,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isFrameworkReady && !isExtracting,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.launch), style = MaterialTheme.typography.titleMedium)
                    }
                }


                // Bottom spacer
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun GameIcon(drawable: Drawable?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (drawable != null) {
            val bitmap = remember(drawable) {
                runCatching { drawable.toBitmap().asImageBitmap() }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Filled.SportsEsports, null,
                    modifier = Modifier.fillMaxSize(0.6f),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        } else {
            Icon(Icons.Filled.SportsEsports, null,
                modifier = Modifier.fillMaxSize(0.6f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
}
