package com.bepinex.android.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bepinex.android.R
import com.bepinex.android.log.BepInExLogReader
import kotlinx.coroutines.launch

/**
 * Semi-transparent floating log overlay showing BepInEx runtime logs.
 */
@Composable
fun LogOverlay(
    logLines: List<BepInExLogReader.LogLine>,
    showOverlay: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = showOverlay,
        enter = fadeIn() + slideInVertically { it / 4 },
        exit = fadeOut() + slideOutVertically { it / 4 }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(onClick = onDismiss)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.8f)
                    .align(Alignment.Center)
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Terminal, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.log_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        // Copy button
                        val clipboardManager = LocalClipboardManager.current
                        IconButton(onClick = {
                            val text = logLines.joinToString("\n") { "${it.level.label}: ${it.message}" }
                            clipboardManager.setText(AnnotatedString(text))
                        }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.ContentCopy, stringResource(R.string.log_copy),
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, stringResource(R.string.close),
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                    // Log content
                    if (logLines.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.log_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        val listState = rememberLazyListState()
                        val scope = rememberCoroutineScope()

                        // Auto-scroll to bottom
                        LaunchedEffect(logLines.size) {
                            if (logLines.isNotEmpty()) {
                                scope.launch {
                                    listState.animateScrollToItem(logLines.size - 1)
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(logLines.size) { idx ->
                                LogLineItem(logLines[idx])
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(line: BepInExLogReader.LogLine) {
    val lineColor = when (line.level) {
        BepInExLogReader.LogLevel.FATAL,
        BepInExLogReader.LogLevel.ERROR -> MaterialTheme.colorScheme.error
        BepInExLogReader.LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        BepInExLogReader.LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp, horizontal = 4.dp)
    ) {
        // Level badge
        Text(
            when (line.level) {
                BepInExLogReader.LogLevel.FATAL -> "F"
                BepInExLogReader.LogLevel.ERROR -> "E"
                BepInExLogReader.LogLevel.WARNING -> "W"
                BepInExLogReader.LogLevel.MESSAGE -> "M"
                BepInExLogReader.LogLevel.INFO -> "I"
                BepInExLogReader.LogLevel.DEBUG -> "D"
                else -> "?"
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = lineColor,
            modifier = Modifier.width(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        // Source
        if (line.source.isNotEmpty()) {
            Text(
                "[${line.source}] ",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
        // Message
        Text(
            line.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = lineColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

