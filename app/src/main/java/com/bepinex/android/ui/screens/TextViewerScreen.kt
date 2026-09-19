package com.bepinex.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.ui.components.CodeEditor
import com.bepinex.android.ui.components.SyntaxLanguage
import com.bepinex.android.ui.components.syntaxLanguageFor
import com.bepinex.android.ui.components.syntaxLanguageLabel
import java.io.File

private val ViewerPreviewSource = """
--[[ Example ]]
local function greet(name)
  -- Lua highlighting
  print("Hello, " .. name)
  return { enabled = true, count = 3, label = nil }
end
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerScreen(
    file: File,
    onNavigateBack: () -> Unit,
    onSave: ((File, String) -> Boolean)? = null,
    onSettingsClick: () -> Unit = {},
    wordWrap: Boolean = true,
    showLineNumbers: Boolean = true
) {
    val initialContent = remember(file) {
        runCatching { file.readText() }.getOrDefault("")
    }
    var textFieldValue by remember(file) {
        mutableStateOf(TextFieldValue(initialContent))
    }
    val hasChanges = textFieldValue.text != initialContent
    var showDiscardDialog by remember(file) { mutableStateOf(false) }
    var saveFailed by remember(file) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val language = remember(file.extension) { syntaxLanguageFor(file.extension) }
    val languageLabel = remember(language, file.extension) {
        syntaxLanguageLabel(language, file.extension)
    }

    fun requestDismiss() {
        if (hasChanges && onSave != null) showDiscardDialog = true else onNavigateBack()
    }

    BackHandler(enabled = !showDiscardDialog, onBack = ::requestDismiss)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = buildString {
                                append(languageLabel)
                                if (hasChanges) {
                                    append(" · ")
                                    append(stringResource(R.string.config_editor_unsaved))
                                }
                            },
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasChanges) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (language != SyntaxLanguage.PLAIN_TEXT) FontWeight.Medium
                            else FontWeight.Normal
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestDismiss) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (onSave != null) {
                        IconButton(
                            onClick = {
                                val saved = runCatching { onSave(file, textFieldValue.text) }.getOrDefault(false)
                                if (saved) {
                                    saveFailed = false
                                    keyboardController?.hide()
                                } else {
                                    saveFailed = true
                                }
                            },
                            enabled = hasChanges
                        ) {
                            Icon(
                                Icons.Filled.Save,
                                stringResource(R.string.config_editor_save),
                                tint = if (hasChanges) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (saveFailed) {
                Text(
                    stringResource(R.string.config_editor_save_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            CodeEditor(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    saveFailed = false
                },
                extension = file.extension,
                wordWrap = wordWrap,
                showLineNumbers = showLineNumbers,
                readOnly = onSave == null,
                placeholder = stringResource(R.string.viewer_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.config_editor_discard_title)) },
            text = { Text(stringResource(R.string.config_editor_discard_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    keyboardController?.hide()
                    onNavigateBack()
                }) {
                    Text(stringResource(R.string.confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.confirm_no))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerSettingsScreen(
    onNavigateBack: () -> Unit,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
    onWordWrapChange: (Boolean) -> Unit,
    onLineNumbersChange: (Boolean) -> Unit
) {
    BackHandler { onNavigateBack() }
    val previewValue = remember { TextFieldValue(ViewerPreviewSource) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.viewer_settings)) },
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
                            Text(stringResource(R.string.viewer_word_wrap))
                            Text(
                                stringResource(R.string.viewer_word_wrap_desc),
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
                            Text(stringResource(R.string.viewer_line_numbers))
                            Text(
                                stringResource(R.string.viewer_line_numbers_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = showLineNumbers, onCheckedChange = onLineNumbersChange)
                    }
                }
            }

            Text(
                stringResource(R.string.viewer_preview),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            CodeEditor(
                value = previewValue,
                onValueChange = {},
                extension = "lua",
                wordWrap = wordWrap,
                showLineNumbers = showLineNumbers,
                readOnly = true,
                showStatusBar = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}
