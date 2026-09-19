package com.bepinex.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.bepinex.android.R
import java.io.File

/**
 * Full-screen editor for supported text files in a modpack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorDialog(
    configFile: File,
    onDismiss: () -> Unit,
    onSave: (File, String) -> Boolean
) {
    val initialContent = remember(configFile) {
        runCatching { configFile.readText() }.getOrDefault("")
    }
    var content by remember(configFile) { mutableStateOf(initialContent) }
    val hasChanges = content != initialContent
    var showDiscardDialog by remember(configFile) { mutableStateOf(false) }
    var saveFailed by remember(configFile) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun requestDismiss() {
        if (hasChanges) showDiscardDialog = true else onDismiss()
    }

    // Intercept the system back gesture/key so it follows the same flow as the
    // top-bar back button instead of dismissing the editor immediately.
    BackHandler(enabled = !showDiscardDialog, onBack = ::requestDismiss)

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val highlightColors = remember(isDark) { editorSyntaxColors(isDark) }
    val syntaxHighlighting = remember(configFile.extension, highlightColors) {
        SyntaxHighlightVisualTransformation(configFile.extension, highlightColors)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            configFile.name,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (hasChanges) {
                            Text(
                                stringResource(R.string.config_editor_unsaved),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestDismiss) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val saved = runCatching { onSave(configFile, content) }.getOrDefault(false)
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
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            if (saveFailed) {
                Text(
                    stringResource(R.string.config_editor_save_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            OutlinedTextField(
                value = content,
                onValueChange = {
                    content = it
                    saveFailed = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                visualTransformation = syntaxHighlighting,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                ),
                singleLine = false,
                minLines = 8,
                maxLines = Int.MAX_VALUE,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default
                ),
                keyboardActions = KeyboardActions.Default,

                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
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
                    onDismiss()
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
