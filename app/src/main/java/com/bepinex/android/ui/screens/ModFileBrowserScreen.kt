package com.bepinex.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import java.io.File

private val EDITABLE_TEXT_EXTENSIONS = setOf(
    "cfg", "conf", "config", "ini", "json", "json5", "log", "lua", "txt",
    "xml", "yaml", "yml", "toml", "properties", "md", "csv", "cs", "js",
    "ts", "sh", "bat", "ps1"
)

private fun isEditableTextFile(file: File): Boolean =
    file.isFile && file.extension.lowercase() in EDITABLE_TEXT_EXTENSIONS

private fun canonicalPath(file: File): String =
    runCatching { file.canonicalPath }.getOrElse { file.absoluteFile.path }

private fun isInsideRoot(file: File, root: File): Boolean {
    val rootPath = canonicalPath(root).trimEnd(File.separatorChar) + File.separator
    val filePath = canonicalPath(file)
    return filePath == canonicalPath(root) || filePath.startsWith(rootPath)
}

/** Only the metadata file at the modpack root is protected. */
private fun isProtectedModpackFile(file: File, root: File): Boolean {
    val parent = file.parentFile ?: return false
    return file.isFile && file.name.equals("modpack.json", ignoreCase = true) &&
        canonicalPath(parent) == canonicalPath(root)
}

private fun fileTypeLabel(file: File): String = when {
    file.isDirectory -> ""
    file.extension.isNotBlank() -> file.extension.uppercase()
    else -> "FILE"
}

/**
 * Browses all files and directories below a modpack's root directory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModFileBrowserScreen(
    rootDirectory: File,
    onNavigateBack: () -> Unit,
    onDirectoryChanged: (File) -> Unit = {},
    onFileClick: (File) -> Unit = {},
    onDeleteFile: (File) -> Boolean = { false }
) {
    val browserRootDirectory = remember(rootDirectory.absolutePath) {
        rootDirectory.absoluteFile
    }
    val rootPath = remember(browserRootDirectory) { canonicalPath(browserRootDirectory) }
    var currentDirectoryPath by rememberSaveable(rootPath) { mutableStateOf(rootPath) }
    var browserRefreshKey by remember { mutableStateOf(0) }
    var filePendingDeletion by remember { mutableStateOf<File?>(null) }
    var deleteFailed by remember { mutableStateOf(false) }

    val currentDirectory = remember(currentDirectoryPath) { File(currentDirectoryPath) }
    val currentDirectoryIsSafe = isInsideRoot(currentDirectory, browserRootDirectory)
    val entries = remember(currentDirectory.absolutePath, browserRefreshKey) {
        if (!currentDirectoryIsSafe || !currentDirectory.isDirectory) {
            emptyList()
        } else {
            currentDirectory.listFiles()
                ?.filter { isInsideRoot(it, browserRootDirectory) }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?: emptyList()
        }
    }
    val canNavigateUp = currentDirectoryPath != rootPath && currentDirectoryIsSafe

    fun navigateUp() {
        val parent = currentDirectory.parentFile
        if (canNavigateUp && parent != null && isInsideRoot(parent, browserRootDirectory)) {
            currentDirectoryPath = canonicalPath(parent)
            onDirectoryChanged(parent)
        } else if (currentDirectoryPath == rootPath || !currentDirectoryIsSafe) {
            onNavigateBack()
        }
    }

    BackHandler { navigateUp() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (canNavigateUp) currentDirectory.name
                            else stringResource(R.string.mod_file_browser_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (canNavigateUp) {
                            Text(
                                relativePath(currentDirectory, browserRootDirectory),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::navigateUp) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.mod_file_browser_title)
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            !currentDirectoryIsSafe || !currentDirectory.isDirectory -> BrowserEmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                text = stringResource(R.string.mod_file_browser_missing)
            )
            entries.isEmpty() -> BrowserEmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                text = stringResource(R.string.mod_file_browser_empty)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
            ) {
                items(entries, key = { canonicalPath(it) }, contentType = { if (it.isDirectory) "directory" else "file" }) { entry ->
                    val isProtected = isProtectedModpackFile(entry, browserRootDirectory)
                    val canEdit = !isProtected && isEditableTextFile(entry)
                    val typeLabel = fileTypeLabel(entry)
                    val accessibilityDescription = when {
                        entry.isDirectory -> entry.name
                        isProtected -> "${entry.name}, ${stringResource(R.string.mod_file_browser_protected)}"
                        else -> "${entry.name}, $typeLabel"
                    }
                    val clickEnabled = entry.isDirectory || canEdit

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = accessibilityDescription
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isProtected) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        enabled = clickEnabled,
                        onClick = {
                            when {
                                entry.isDirectory -> {
                                    currentDirectoryPath = canonicalPath(entry)
                                    onDirectoryChanged(entry)
                                }
                                canEdit -> onFileClick(entry)
                            }
                        }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                else if (isProtected) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        entry.isDirectory -> stringResource(R.string.mod_file_browser_directory)
                                        isProtected -> stringResource(R.string.mod_file_browser_protected_file)
                                        canEdit -> stringResource(R.string.mod_file_browser_editable_file, "$typeLabel · ${formatFileSize(entry.length())}")
                                        else -> stringResource(R.string.mod_file_browser_unsupported_file, "$typeLabel · ${formatFileSize(entry.length())}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            when {
                                entry.isDirectory -> Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                canEdit -> Icon(Icons.Filled.EditNote, stringResource(R.string.mod_file_browser_edit_file), tint = MaterialTheme.colorScheme.primary)
                                isProtected -> Icon(Icons.Filled.Lock, stringResource(R.string.mod_file_browser_protected), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (entry.isFile && !isProtected && isInsideRoot(entry, browserRootDirectory)) {
                                IconButton(
                                    onClick = {
                                        deleteFailed = false
                                        filePendingDeletion = entry
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        stringResource(R.string.mod_file_browser_delete_file),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    filePendingDeletion?.let { file ->
        val stillAllowed = isInsideRoot(file, browserRootDirectory) &&
            file.isFile && !isProtectedModpackFile(file, browserRootDirectory)
        AlertDialog(
            onDismissRequest = {
                filePendingDeletion = null
                deleteFailed = false
            },
            title = { Text(stringResource(R.string.mod_file_browser_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mod_file_browser_delete_message, file.name))
                    if (!stillAllowed || deleteFailed) {
                        Text(
                            if (!stillAllowed) stringResource(R.string.mod_file_browser_protected_file)
                            else stringResource(R.string.mod_file_browser_delete_failed),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = stillAllowed,
                    onClick = {
                        if (stillAllowed && onDeleteFile(file)) {
                            filePendingDeletion = null
                            deleteFailed = false
                            browserRefreshKey++
                        } else {
                            deleteFailed = true
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    filePendingDeletion = null
                    deleteFailed = false
                }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }
}

@Composable
private fun BrowserEmptyState(modifier: Modifier, text: String) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun relativePath(file: File, root: File): String =
    runCatching {
        File(canonicalPath(file)).relativeTo(File(canonicalPath(root))).path
    }.getOrDefault(file.name)
