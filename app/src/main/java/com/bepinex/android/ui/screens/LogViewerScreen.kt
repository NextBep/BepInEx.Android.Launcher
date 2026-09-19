package com.bepinex.android.ui.screens

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bepinex.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_REFRESH_INTERVAL_MS = 1_000L
private const val LOG_SCROLL_BOTTOM_THRESHOLD = 48

private data class LogReadResult(
    val content: String,
    val exists: Boolean,
    val error: String? = null
)

private suspend fun readLogFile(path: String): LogReadResult = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(path)
        if (!file.exists()) {
            LogReadResult(content = "", exists = false)
        } else {
            LogReadResult(content = file.readText(), exists = true)
        }
    }.getOrElse { error ->
        LogReadResult(
            content = "",
            exists = true,
            error = error.message ?: error.javaClass.simpleName
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    logFilePath: String,
    onNavigateBack: () -> Unit,
    onSettingsClick: () -> Unit = {},
    autoScroll: Boolean = true,
    wordWrap: Boolean = false,
    showLineNumbers: Boolean = false,
    onAutoScrollChange: (Boolean) -> Unit = {},
    onWordWrapChange: (Boolean) -> Unit = {},
    onLineNumbersChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val refreshScope = rememberCoroutineScope()
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    var readResult by remember(logFilePath) {
        mutableStateOf(LogReadResult(content = "", exists = false))
    }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun refreshLog() {
        if (isRefreshing) return
        isRefreshing = true
        val previousContent = readResult.content
        val wasAtBottom = verticalScrollState.maxValue == 0 ||
            verticalScrollState.value >= verticalScrollState.maxValue - LOG_SCROLL_BOTTOM_THRESHOLD
        val result = readLogFile(logFilePath)
        readResult = result
        isRefreshing = false

        if (autoScroll && wasAtBottom && result.content != previousContent) {
            withFrameNanos { }
            verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
        }
    }

    LaunchedEffect(logFilePath) {
        while (true) {
            refreshLog()
            delay(LOG_REFRESH_INTERVAL_MS)
        }
    }

    val shareLog: () -> Unit = {
        val file = File(logFilePath)
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
        } else {
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_title))
                    clipData = ClipData.newRawUri(context.getString(R.string.log_title), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(shareIntent, context.getString(R.string.log_share))
                )
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "${context.getString(R.string.error)}: ${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (!isRefreshing) refreshScope.launch { refreshLog() } },
                        enabled = !isRefreshing
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh)
                        )
                    }
                    IconButton(onClick = shareLog) {
                        Icon(Icons.Filled.Share, stringResource(R.string.log_share))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.viewer_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                when {
                    readResult.error != null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "${stringResource(R.string.error)}: ${readResult.error}",
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    !readResult.exists || readResult.content.isBlank() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.log_empty),
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> {
                        SelectionContainer {
                            val logLines = remember(readResult.content) { readResult.content.split("\n") }
                            var logVisualLineCount by remember { mutableIntStateOf(logLines.size) }
                            val logLineNumberWidth = remember(logVisualLineCount) { "${logVisualLineCount}".length * 8 + 4 }

                            if (wordWrap) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(verticalScrollState)
                                ) {
                                    if (showLineNumbers) {
                                        Column(
                                            modifier = Modifier
                                                .width(logLineNumberWidth.dp)
                                                .padding(top = 12.dp, bottom = 12.dp)
                                        ) {
                                            for (i in 1..logVisualLineCount) {
                                                Text(
                                                    text = "$i",
                                                    fontSize = 11.sp,
                                                    lineHeight = 16.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.padding(end = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = readResult.content,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        onTextLayout = { logVisualLineCount = it.lineCount }
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .horizontalScroll(horizontalScrollState)
                                        .verticalScroll(verticalScrollState)
                                ) {
                                    if (showLineNumbers) {
                                        Column(
                                            modifier = Modifier
                                                .width(logLineNumberWidth.dp)
                                                .padding(top = 12.dp, bottom = 12.dp)
                                        ) {
                                            for (i in 1..logVisualLineCount) {
                                                Text(
                                                    text = "$i",
                                                    fontSize = 11.sp,
                                                    lineHeight = 16.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.padding(end = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = readResult.content,
                                        modifier = Modifier
                                            .padding(start = 0.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        onTextLayout = { logVisualLineCount = it.lineCount }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerSettingsScreen(
    onNavigateBack: () -> Unit,
    autoScroll: Boolean,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    onWordWrapChange: (Boolean) -> Unit,
    onLineNumbersChange: (Boolean) -> Unit
) {
    BackHandler { onNavigateBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_settings)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.log_auto_scroll))
                            Text(
                                stringResource(R.string.log_auto_scroll_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = autoScroll, onCheckedChange = onAutoScrollChange)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.log_word_wrap))
                            Text(
                                stringResource(R.string.log_word_wrap_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = wordWrap, onCheckedChange = onWordWrapChange)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.log_line_numbers))
                            Text(
                                stringResource(R.string.log_line_numbers_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = showLineNumbers, onCheckedChange = onLineNumbersChange)
                    }
                }
            }
        }
    }
}
