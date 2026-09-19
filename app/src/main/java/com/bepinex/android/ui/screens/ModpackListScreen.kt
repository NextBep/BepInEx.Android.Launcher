package com.bepinex.android.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bepinex.android.modpack.ModpackManager
import com.bepinex.android.modpack.ModpackMeta
import com.bepinex.android.shortcut.ModpackShortcutHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModpackListScreen(
    packageName: String,
    targetGameLabel: String,
    gameVersion: String,
    modpacks: List<ModpackMeta>,
    activeModpackName: String?,
    isSwitching: Boolean = false,
    iconRefreshKey: Int = 0,
    onCreateModpack: (String, Boolean, android.graphics.Bitmap?, String) -> Unit,
    onDeleteModpack: (String) -> Unit,
    onEditModpack: (String, String, Boolean, android.graphics.Bitmap?, String) -> Unit,
    onSelectModpack: (String?) -> Unit,
    onOpenModpack: (String) -> Unit,
    onExportModpack: (String) -> Unit,
    onImportModpack: () -> Unit,
    onRefresh: () -> Unit = {},
    onImportDownloadFiles: (List<File>) -> Unit = {}
) {
    val context = LocalContext.current
    val manager = remember { ModpackManager() }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showEditDialog by remember { mutableStateOf<ModpackMeta?>(null) }
    var showFabCreateDialog by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var editingIconForModpack by remember { mutableStateOf<String?>(null) }
    var downloadCandidates by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedDownloadPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanningDownloads by remember { mutableStateOf(false) }
    val scanScope = rememberCoroutineScope()

    // Refresh icons when modpacks change
    var internalIconRefreshKey by remember { mutableIntStateOf(0) }
    val combinedIconRefreshKey = iconRefreshKey + internalIconRefreshKey

    fun scanDownloads() {
        if (scanningDownloads) return
        scanningDownloads = true
        scanScope.launch {
            val found = withContext(Dispatchers.IO) {
                manager.scanDownloadModpacks(context)
            }
            scanningDownloads = false
            if (found.isEmpty()) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.modpack_scan_downloads_empty),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                downloadCandidates = found
                selectedDownloadPaths = found.map { it.absolutePath }.toSet()
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val targetName = editingIconForModpack ?: return@rememberLauncherForActivityResult
        uri?.let {
            val ext = when {
                it.toString().endsWith(".jpg", true) -> "jpg"
                it.toString().endsWith(".jpeg", true) -> "jpg"
                it.toString().endsWith(".webp", true) -> "webp"
                else -> "png"
            }
            context.contentResolver.openInputStream(it)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    manager.saveModpackIcon(packageName, targetName, bitmap, ext)
                    internalIconRefreshKey++
                }
            }
        }
        editingIconForModpack = null
    }

    fun collapseActions() {
        actionsExpanded = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.modpack_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh)
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ModpackActionsFab(
                expanded = actionsExpanded,
                onExpandedChange = { actionsExpanded = it },
                onCreate = {
                    collapseActions()
                    showFabCreateDialog = true
                },
                onImport = {
                    collapseActions()
                    onImportModpack()
                },
                onAutoImport = {
                    collapseActions()
                    scanDownloads()
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isSwitching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.modpack_switching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
        ) {
            if (targetGameLabel.isNotBlank()) {
                item(key = "game_label") {
                    Text(
                        text = stringResource(R.string.modpack_target_game, targetGameLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            item(key = "vanilla") {
                VanillaCard(
                    isActive = activeModpackName == null,
                    onSelect = { onSelectModpack(null) },
                )
            }

            if (modpacks.isEmpty()) {
                item {
                    EmptyModpacksCard()
                }
            } else {
                items(modpacks, key = { it.name }) { modpack ->
                    val isFirst = modpack.name == modpacks.first().name
                    // Force recomposition when internalIconRefreshKey changes
                    key(combinedIconRefreshKey) {
                        ModpackCard(
                            modpack = modpack,
                            packageName = packageName,
                            gameVersion = gameVersion,
                            iconRefreshKey = combinedIconRefreshKey,
                            isActive = activeModpackName == modpack.name,
                            onOpen = { onOpenModpack(modpack.name) },
                            onSelect = { onSelectModpack(modpack.name) },
                            onIconClick = {
                                editingIconForModpack = modpack.name
                                imagePicker.launch("image/*")
                            },
                            onEdit = { showEditDialog = modpack },
                            onDelete = { showDeleteDialog = modpack.name },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
        }
    }

    // Delete dialog
    showDeleteDialog?.let { name ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.modpack_confirm_delete_title)) },
            text = { Text(stringResource(R.string.modpack_confirm_delete_msg, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteModpack(name)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }

    // Edit dialog (name + shortcut only)
    showEditDialog?.let { modpack ->
        EditModpackDialog(
            modpack = modpack,
            packageName = packageName,
            currentGameVersion = gameVersion,
            onDismiss = { showEditDialog = null },
            onSave = { newName, createShortcut, bitmap, packGameVersion ->
                onEditModpack(modpack.name, newName, createShortcut, bitmap, packGameVersion)
                showEditDialog = null
            }
        )
    }

    if (downloadCandidates.isNotEmpty()) {
        DownloadModpackScanDialog(
            files = downloadCandidates,
            selectedPaths = selectedDownloadPaths,
            onToggle = { file, checked ->
                selectedDownloadPaths = if (checked) {
                    selectedDownloadPaths + file.absolutePath
                } else {
                    selectedDownloadPaths - file.absolutePath
                }
            },
            onImport = {
                val selected = downloadCandidates.filter { it.absolutePath in selectedDownloadPaths }
                downloadCandidates = emptyList()
                if (selected.isNotEmpty()) onImportDownloadFiles(selected)
            },
            onSkip = {
                downloadCandidates = emptyList()
            }
        )
    }

    // Create dialog
    if (showFabCreateDialog) {
        CreateModpackDialog(
            targetGame = targetGameLabel,
            currentGameVersion = gameVersion,
            onDismiss = { showFabCreateDialog = false },
            onCreate = { name, createShortcut, bitmap, packGameVersion ->
                onCreateModpack(name, createShortcut, bitmap, packGameVersion)
                showFabCreateDialog = false
            }
        )
    }
}

@Composable
private fun VanillaCard(
    isActive: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.modpack_vanilla),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.modpack_vanilla_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive) {
                    Spacer(Modifier.height(6.dp))
                    ActiveBadge()
                }
            }
            Spacer(Modifier.width(8.dp))
            RadioButton(selected = isActive, onClick = onSelect)
        }
    }
}

@Composable
private fun ModpackCard(
    modpack: ModpackMeta,
    packageName: String,
    gameVersion: String,
    iconRefreshKey: Int,
    isActive: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onIconClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val manager = remember { ModpackManager() }
    var iconBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // Reload icon whenever iconRefreshKey changes
    LaunchedEffect(iconRefreshKey, modpack.name) {
        iconBitmap = withContext(Dispatchers.IO) {
            val file = manager.getModpackIconFile(packageName, modpack.name)
            if (file != null && file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onIconClick() },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val bmp = iconBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.modpack_icon),
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.FolderZip,
                                contentDescription = stringResource(R.string.modpack_icon),
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modpack.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.modpack_mod_count_ratio,
                            modpack.enabledModCount,
                            modpack.modCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    val versionCompatible = ModpackManager.isGameVersionCompatible(
                        modpack.gameVersion,
                        gameVersion
                    )
                    Text(
                        text = if (modpack.gameVersion.isBlank()) {
                            stringResource(R.string.modpack_game_version_unset)
                        } else {
                            stringResource(R.string.modpack_game_version_value, modpack.gameVersion)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!versionCompatible) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isActive) {
                        Spacer(Modifier.height(6.dp))
                        ActiveBadge()
                    }
                }
                Spacer(Modifier.width(8.dp))
                RadioButton(selected = isActive, onClick = onSelect)
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.modpack_edit))
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun ActiveBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    ) {
        Text(
            text = stringResource(R.string.modpack_active),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun EditModpackDialog(
    modpack: ModpackMeta,
    packageName: String,
    currentGameVersion: String,
    onDismiss: () -> Unit,
    onSave: (newName: String, createShortcut: Boolean, bitmap: android.graphics.Bitmap?, gameVersion: String) -> Unit
) {
    val context = LocalContext.current
    val manager = remember { ModpackManager() }
    var name by remember(modpack.name) { mutableStateOf(modpack.name) }
    var gameVersion by remember(modpack.name) { mutableStateOf(modpack.gameVersion) }
    var renameFailed by remember(modpack.name) { mutableStateOf(false) }
    var iconBitmap by remember(modpack.name) {
        mutableStateOf(
            manager.getModpackIconFile(packageName, modpack.name)?.let {
                BitmapFactory.decodeFile(it.absolutePath)
            }
        )
    }
    var hasNewIcon by remember(modpack.name) { mutableStateOf(false) }
    var showPermissionDialog by remember(modpack.name) { mutableStateOf(false) }
    val trimmedName = name.trim()

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                iconBitmap = BitmapFactory.decodeStream(stream)
                hasNewIcon = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modpack_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .clickable { imagePicker.launch("image/*") },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.AddAPhoto,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.modpack_icon),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.modpack_icon_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        renameFailed = false
                    },
                    label = { Text(stringResource(R.string.modpack_name_hint)) },
                    singleLine = true,
                    isError = renameFailed,
                    supportingText = if (renameFailed) {
                        { Text(stringResource(R.string.modpack_rename_failed)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = gameVersion,
                    onValueChange = { gameVersion = it },
                    label = { Text(stringResource(R.string.modpack_game_version)) },
                    placeholder = {
                        Text(
                            currentGameVersion.ifBlank {
                                stringResource(R.string.modpack_game_version_any)
                            }
                        )
                    },
                    supportingText = { Text(stringResource(R.string.modpack_game_version_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ShortcutActionButton(
                    enabled = trimmedName.isNotEmpty(),
                    onClick = {
                        if (!ModpackShortcutHelper.hasShortcutPermission(context)) {
                            showPermissionDialog = true
                        } else {
                            onSave(trimmedName, true, if (hasNewIcon) iconBitmap else null, gameVersion.trim())
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trimmedName.isEmpty()) {
                        renameFailed = true
                    } else {
                        onSave(trimmedName, false, if (hasNewIcon) iconBitmap else null, gameVersion.trim())
                    }
                },
                enabled = trimmedName.isNotEmpty()
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm_cancel))
            }
        }
    )

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.shortcut_permission_title)) },
            text = { Text(stringResource(R.string.shortcut_permission_guide)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    ModpackShortcutHelper.openShortcutPermissionSettings(context)
                }) {
                    Text(stringResource(R.string.shortcut_permission_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }
}

@Composable
private fun ModpackActionsFab(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onAutoImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        label = "modpackFabRotation"
    )

    Box(modifier = modifier) {
        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(
                    if (expanded) R.string.modpack_actions_close else R.string.modpack_actions
                ),
                modifier = Modifier.rotate(rotation)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            offset = DpOffset(0.dp, (-8).dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.modpack_create)) },
                onClick = onCreate,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.modpack_import_manual)) },
                onClick = onImport,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.FileOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.modpack_import_auto_scan)) },
                onClick = onAutoImport,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
        }
    }
}

@Composable
private fun EmptyModpacksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = stringResource(R.string.modpack_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.modpack_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun DownloadModpackScanDialog(
    files: List<File>,
    selectedPaths: Set<String>,
    onToggle: (File, Boolean) -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.modpack_scan_downloads_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.modpack_scan_downloads_message, files.size))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(files, key = { it.absolutePath }) { file ->
                        val checked = file.absolutePath in selectedPaths
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(file, !checked) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggle(file, it) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatDownloadFileSize(file.length()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onImport,
                enabled = selectedPaths.isNotEmpty()
            ) {
                Text(stringResource(R.string.modpack_scan_downloads_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.modpack_scan_downloads_skip))
            }
        }
    )
}

private fun formatDownloadFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
fun CreateModpackDialog(
    targetGame: String,
    currentGameVersion: String,
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, android.graphics.Bitmap?, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var gameVersion by remember { mutableStateOf(currentGameVersion) }
    var createShortcut by remember { mutableStateOf(false) }
    var iconBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    val manager = remember { ModpackManager() }
    val trimmedName = name.trim()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                iconBitmap = BitmapFactory.decodeStream(stream)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modpack_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .clickable { imagePicker.launch("image/*") },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.AddAPhoto,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.modpack_icon),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.modpack_icon_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Game target
                Text(
                    text = stringResource(R.string.modpack_target_game, targetGame),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.modpack_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = gameVersion,
                    onValueChange = { gameVersion = it },
                    label = { Text(stringResource(R.string.modpack_game_version)) },
                    supportingText = { Text(stringResource(R.string.modpack_game_version_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ShortcutActionButton(
                    enabled = trimmedName.isNotEmpty(),
                    onClick = {
                        if (!ModpackShortcutHelper.hasShortcutPermission(context)) {
                            showPermissionDialog = true
                        } else {
                            onCreate(trimmedName, true, iconBitmap, gameVersion.trim())
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trimmedName.isNotEmpty()) {
                        onCreate(trimmedName, createShortcut, iconBitmap, gameVersion.trim())
                    }
                },
                enabled = trimmedName.isNotEmpty()
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm_cancel))
            }
        }
    )

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(R.string.shortcut_permission_title)) },
            text = { Text(stringResource(R.string.shortcut_permission_guide)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    ModpackShortcutHelper.openShortcutPermissionSettings(context)
                }) {
                    Text(stringResource(R.string.shortcut_permission_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }
}

@Composable
private fun ShortcutActionButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            imageVector = Icons.Filled.AddToHomeScreen,
            contentDescription = null
        )
        Spacer(Modifier.width(8.dp))
        Text(text = stringResource(R.string.modpack_shortcut_create))
    }
}
