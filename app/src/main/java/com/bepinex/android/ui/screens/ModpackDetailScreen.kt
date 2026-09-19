package com.bepinex.android.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.bepinex.android.R
import com.bepinex.android.modpack.ModpackManager
import com.bepinex.android.modpack.ModpackMeta
import com.bepinex.android.modpack.ModpackMod
import java.io.File

/**
 * Manage mods within a specific modpack and edit config files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModpackDetailScreen(
    packageName: String,
    modpackName: String,
    mods: List<ModpackMod>,
    configFiles: List<File>,
    onNavigateBack: () -> Unit,
    onAddMod: () -> Unit,
    onDeleteMod: (ModpackMod) -> Unit,
    onRenameMod: (ModpackMod, String) -> Unit,
    onSetModCategory: (ModpackMod, String?) -> Unit,
    onToggleMod: (ModpackMod, Boolean) -> Unit,
    onOpenConfig: (File) -> Unit,
    onViewLog: () -> Unit,
    onExportModpack: () -> Unit,
    onBrowseModFiles: () -> Unit = {},
    onImportDownloadDlls: (List<File>) -> Unit = {},
    onImportModsFromModpack: (sourceModpack: String, relativePaths: List<String>) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val manager = remember { ModpackManager() }
    val scanScope = rememberCoroutineScope()
    var modPendingDelete by remember { mutableStateOf<ModpackMod?>(null) }
    var modPendingRename by remember { mutableStateOf<ModpackMod?>(null) }
    var modPendingCategory by remember { mutableStateOf<ModpackMod?>(null) }
    var sortMode by remember { mutableStateOf(ModSortMode.DISPLAY_NAME) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<ModCategoryFilter>(ModCategoryFilter.All) }
    var downloadCandidates by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedDownloadPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanningDownloads by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var otherModpacks by remember { mutableStateOf<List<ModpackMeta>>(emptyList()) }
    var importSourceName by remember { mutableStateOf<String?>(null) }
    var importSourceMods by remember { mutableStateOf<List<ModpackMod>>(emptyList()) }
    var selectedImportPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val sortedMods = remember(mods, sortMode) { mods.sortedWith(sortMode.comparator) }
    val categoryNames = remember(mods) {
        mods.mapNotNull { it.category?.takeIf(String::isNotBlank) }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val hasUncategorized = remember(mods) { mods.any { it.category.isNullOrBlank() } }
    val activeCategoryFilter = when (val filter = categoryFilter) {
        is ModCategoryFilter.Named ->
            if (filter.name in categoryNames) filter else ModCategoryFilter.All
        ModCategoryFilter.Uncategorized ->
            if (hasUncategorized) filter else ModCategoryFilter.All
        ModCategoryFilter.All -> filter
    }
    val filteredMods = remember(sortedMods, searchQuery, activeCategoryFilter) {
        sortedMods.filter { it.matchesSearch(searchQuery) && it.matchesCategory(activeCategoryFilter) }
    }
    val filteredConfigs = remember(configFiles, searchQuery, activeCategoryFilter) {
        if (activeCategoryFilter != ModCategoryFilter.All) {
            emptyList()
        } else {
            configFiles.filter { it.matchesSearch(searchQuery) }
        }
    }
    val listState = rememberLazyListState()
    val showScrollToTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120
        }
    }

    fun scanDownloads() {
        if (scanningDownloads) return
        scanningDownloads = true
        scanScope.launch {
            try {
                val existingNames = mods.map { it.file.name.lowercase() }.toSet()
                val found = withContext(Dispatchers.IO) {
                    manager.scanDownloadDlls(context)
                        .filter { it.name.lowercase() !in existingNames }
                }
                if (found.isEmpty()) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.modpack_scan_dlls_empty),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    downloadCandidates = found
                    selectedDownloadPaths = found.map { it.absolutePath }.toSet()
                }
            } finally {
                scanningDownloads = false
            }
        }
    }

    fun openImportFromModpack() {
        val packs = manager.listModpacks(packageName).filter { it.name != modpackName }
        if (packs.isEmpty()) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.modpack_import_from_other_empty),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        otherModpacks = packs
        importSourceName = null
        importSourceMods = emptyList()
        selectedImportPaths = emptySet()
    }

    fun selectImportSource(sourceName: String) {
        scanScope.launch {
            val entries = withContext(Dispatchers.IO) {
                manager.listModEntries(packageName, sourceName)
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ModpackMod::displayName))
            }
            otherModpacks = emptyList()
            if (entries.isEmpty()) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.modpack_import_from_other_no_mods),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            importSourceName = sourceName
            importSourceMods = entries
            selectedImportPaths = entries.map { it.relativePath }.toSet()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = modpackName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onViewLog) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = stringResource(R.string.log_title)
                        )
                    }
                    IconButton(onClick = onExportModpack) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.modpack_export)
                        )
                    }
                    AddModActionsButton(
                        expanded = addMenuOpen,
                        onExpandedChange = { addMenuOpen = it },
                        scanning = scanningDownloads,
                        onManualImport = onAddMod,
                        onAutoScan = { scanDownloads() },
                        onImportFromModpack = { openImportFromModpack() }
                    )
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                ),
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
                .navigationBarsPadding()
        ) {
            if (scanningDownloads) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            item {
                BrowseModFilesCard(onClick = onBrowseModFiles)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.installed_mods),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() }
                        )
                        if (mods.isNotEmpty()) {
                            StatusBadge(
                                text = stringResource(
                                    R.string.modpack_mod_count_ratio,
                                    mods.count { it.enabled },
                                    mods.size
                                )
                            )
                            Box {
                                IconButton(onClick = { sortMenuOpen = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.Sort,
                                        contentDescription = stringResource(R.string.modpack_mod_sort)
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false }
                                ) {
                                    ModSortMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(mode.labelRes)) },
                                            onClick = {
                                                sortMode = mode
                                                sortMenuOpen = false
                                            },
                                            trailingIcon = if (sortMode == mode) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null
                                                    )
                                                }
                                            } else {
                                                null
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (mods.isNotEmpty()) {
                        ModSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it }
                        )
                        if (categoryNames.isNotEmpty()) {
                            ModCategoryFilterRow(
                                selected = activeCategoryFilter,
                                categoryNames = categoryNames,
                                showUncategorized = hasUncategorized,
                                onSelect = { categoryFilter = it }
                            )
                        }
                    }
                }
            }

            if (mods.isEmpty()) {
                item {
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
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.modpack_no_mods),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onAddMod) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.modpack_add_mod))
                            }
                        }
                    }
                }
            } else if (filteredMods.isEmpty() && filteredConfigs.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.modpack_search_no_results),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = filteredMods,
                    key = { it.file.absolutePath },
                    contentType = { "mod" }
                ) { mod ->
                    ModItemCard(
                        mod = mod,
                        onToggle = { enabled -> onToggleMod(mod, enabled) },
                        onRename = { modPendingRename = mod },
                        onSetCategory = { modPendingCategory = mod },
                        onDelete = { modPendingDelete = mod }
                    )
                }
            }

            if (filteredConfigs.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_section_config),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .semantics { heading() }
                    )
                }
                items(
                    items = filteredConfigs,
                    key = { it.absolutePath },
                    contentType = { "config" }
                ) { cfg ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        onClick = { onOpenConfig(cfg) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = cfg.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
            ListScrollbar(
                listState = listState,
                itemLabels = remember(filteredMods, filteredConfigs, mods, searchQuery, activeCategoryFilter) {
                    buildList {
                        add("")
                        add("")
                        if (mods.isEmpty() || filteredMods.isEmpty()) {
                            add("")
                        } else {
                            addAll(filteredMods.map { it.displayName })
                        }
                        if (filteredConfigs.isNotEmpty()) {
                            add("")
                            addAll(filteredConfigs.map { it.name })
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = 12.dp, bottom = 12.dp, end = 2.dp)
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToTop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 16.dp),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        scanScope.launch { listState.animateScrollToItem(0) }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.modpack_scroll_to_top)
                    )
                }
            }
        }
        }
    }

    modPendingDelete?.let { mod ->
        AlertDialog(
            onDismissRequest = { modPendingDelete = null },
            title = { Text(stringResource(R.string.mod_file_browser_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.mod_file_browser_delete_message,
                        mod.displayName
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMod(mod)
                        modPendingDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.confirm_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { modPendingDelete = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            }
        )
    }

    modPendingRename?.let { mod ->
        RenameDllDialog(
            mod = mod,
            onDismiss = { modPendingRename = null },
            onConfirm = { newName ->
                onRenameMod(mod, newName)
                modPendingRename = null
            }
        )
    }

    modPendingCategory?.let { mod ->
        SetModCategoryDialog(
            mod = mod,
            existingCategories = categoryNames,
            onDismiss = { modPendingCategory = null },
            onConfirm = { category ->
                onSetModCategory(mod, category)
                modPendingCategory = null
            }
        )
    }

    if (downloadCandidates.isNotEmpty()) {
        DownloadDllScanDialog(
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
                if (selected.isNotEmpty()) onImportDownloadDlls(selected)
            },
            onSkip = {
                downloadCandidates = emptyList()
            }
        )
    }

    if (otherModpacks.isNotEmpty()) {
        ImportFromModpackPickerDialog(
            modpacks = otherModpacks,
            onSelect = { selectImportSource(it.name) },
            onDismiss = { otherModpacks = emptyList() }
        )
    }

    importSourceName?.let { sourceName ->
        ImportModsFromModpackDialog(
            sourceName = sourceName,
            mods = importSourceMods,
            selectedPaths = selectedImportPaths,
            onToggle = { mod, checked ->
                selectedImportPaths = if (checked) {
                    selectedImportPaths + mod.relativePath
                } else {
                    selectedImportPaths - mod.relativePath
                }
            },
            onSelectAll = { selectAll ->
                selectedImportPaths = if (selectAll) {
                    importSourceMods.map { it.relativePath }.toSet()
                } else {
                    emptySet()
                }
            },
            onImport = {
                val selected = importSourceMods
                    .map { it.relativePath }
                    .filter { it in selectedImportPaths }
                importSourceName = null
                importSourceMods = emptyList()
                if (selected.isNotEmpty()) {
                    onImportModsFromModpack(sourceName, selected)
                }
            },
            onSkip = {
                importSourceName = null
                importSourceMods = emptyList()
            }
        )
    }
}

@Composable
private fun ListScrollbar(
    listState: LazyListState,
    itemLabels: List<String>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val scrollbarLabel = stringResource(R.string.modpack_scrollbar)
    val density = LocalDensity.current
    val layoutInfo = listState.layoutInfo
    val total = layoutInfo.totalItemsCount
    val visible = layoutInfo.visibleItemsInfo
    if (total <= 1 || visible.isEmpty()) return
    if (!listState.canScrollForward && !listState.canScrollBackward) return

    val scrollFraction = listState.scrollFraction()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastTargetIndex by remember { mutableIntStateOf(-1) }
    val fraction = if (dragging) dragFraction else scrollFraction
    val maxIndex = (total - 1).coerceAtLeast(1)
    val targetIndex = if (dragging) {
        (dragFraction * maxIndex).roundToInt().coerceIn(0, total - 1)
    } else {
        listState.firstVisibleItemIndex.coerceIn(0, total - 1)
    }
    val hint = hintLabel(itemLabels, targetIndex)

    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    val thumbHeight = 48.dp
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val hintSize = 72.dp
    val hintSizePx = with(density) { hintSize.toPx() }
    val scrollJob = remember { ScrollJobHolder() }

    fun jumpTo(rawFraction: Float) {
        val clamped = rawFraction.coerceIn(0f, 1f)
        dragFraction = clamped
        val index = (clamped * maxIndex).roundToInt().coerceIn(0, total - 1)
        if (index == lastTargetIndex) return
        lastTargetIndex = index
        scrollJob.job?.cancel()
        scrollJob.job = scope.launch {
            listState.scrollToItem(index)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(96.dp)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .semantics { contentDescription = scrollbarLabel }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(28.dp)
                .pointerInput(total, travelPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            lastTargetIndex = -1
                            jumpTo((offset.y - thumbHeightPx / 2f) / travelPx)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            jumpTo(dragFraction + dragAmount / travelPx)
                        }
                    )
                }
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .fillMaxHeight()
                .width(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .offset { IntOffset(0, (travelPx * fraction).roundToInt()) }
                .width(8.dp)
                .height(thumbHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        if (dragging && hint.isNotEmpty()) {
            val hintOffsetY = (travelPx * fraction - (hintSizePx - thumbHeightPx) / 2f)
                .coerceIn(0f, (trackHeightPx - hintSizePx).coerceAtLeast(0f))
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 22.dp)
                    .offset { IntOffset(0, hintOffsetY.roundToInt()) }
                    .size(hintSize),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = hint,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun hintLabel(itemLabels: List<String>, index: Int): String {
    fun labelAt(i: Int): String = itemLabels.getOrNull(i)?.trim().orEmpty()
    fun letterOf(label: String): String =
        label.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    letterOf(labelAt(index)).takeIf { it.isNotEmpty() }?.let { return it }
    for (i in index + 1 until itemLabels.size) {
        letterOf(labelAt(i)).takeIf { it.isNotEmpty() }?.let { return it }
    }
    for (i in index - 1 downTo 0) {
        letterOf(labelAt(i)).takeIf { it.isNotEmpty() }?.let { return it }
    }
    return ""
}

private class ScrollJobHolder {
    var job: Job? = null
}

private fun LazyListState.scrollFraction(): Float {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    if (!canScrollForward) return 1f
    if (!canScrollBackward) return 0f
    val maxIndex = (info.totalItemsCount - 1).coerceAtLeast(1)
    return (firstVisibleItemIndex.toFloat() / maxIndex).coerceIn(0f, 0.999f)
}

private fun ModpackMod.matchesSearch(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return displayName.contains(needle, ignoreCase = true) ||
        file.name.contains(needle, ignoreCase = true) ||
        relativePath.contains(needle, ignoreCase = true) ||
        category.orEmpty().contains(needle, ignoreCase = true)
}

private fun ModpackMod.matchesCategory(filter: ModCategoryFilter): Boolean = when (filter) {
    ModCategoryFilter.All -> true
    ModCategoryFilter.Uncategorized -> category.isNullOrBlank()
    is ModCategoryFilter.Named -> category.equals(filter.name, ignoreCase = true)
}

private sealed class ModCategoryFilter {
    data object All : ModCategoryFilter()
    data object Uncategorized : ModCategoryFilter()
    data class Named(val name: String) : ModCategoryFilter()
}

private fun File.matchesSearch(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return name.contains(needle, ignoreCase = true)
}

private enum class ModSortMode(val labelRes: Int) {
    DISPLAY_NAME(R.string.modpack_mod_sort_name),
    FILE_NAME(R.string.modpack_mod_sort_file),
    ENABLED_FIRST(R.string.modpack_mod_sort_enabled_first),
    DISABLED_FIRST(R.string.modpack_mod_sort_disabled_first);

    val comparator: Comparator<ModpackMod>
        get() = when (this) {
            DISPLAY_NAME -> compareBy(String.CASE_INSENSITIVE_ORDER, ModpackMod::displayName)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ModpackMod::relativePath)
            FILE_NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { mod: ModpackMod -> mod.file.name }
                .thenBy(String.CASE_INSENSITIVE_ORDER, ModpackMod::relativePath)
            ENABLED_FIRST -> compareByDescending(ModpackMod::enabled)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ModpackMod::displayName)
            DISABLED_FIRST -> compareBy(ModpackMod::enabled)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ModpackMod::displayName)
        }
}

@Composable
private fun RenameDllDialog(
    mod: ModpackMod,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(mod.relativePath) { mutableStateOf(mod.displayName) }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modpack_mod_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.modpack_mod_file_name, mod.relativePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.modpack_mod_display_name)) },
                    supportingText = {
                        Text(
                            if (trimmed.isEmpty()) {
                                stringResource(R.string.modpack_mod_rename_failed)
                            } else {
                                stringResource(R.string.modpack_mod_display_name_hint)
                            }
                        )
                    },
                    singleLine = true,
                    isError = trimmed.isEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty()
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
}

@Composable
private fun SetModCategoryDialog(
    mod: ModpackMod,
    existingCategories: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var name by remember(mod.relativePath) { mutableStateOf(mod.category.orEmpty()) }
    val trimmed = name.trim()
    val suggestions = remember(existingCategories, mod.category) {
        existingCategories.filter { it != mod.category }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modpack_mod_category_set)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.modpack_mod_file_name, mod.relativePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.modpack_mod_category)) },
                    supportingText = {
                        Text(stringResource(R.string.modpack_mod_category_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (suggestions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.modpack_mod_category_existing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions, key = { it }) { category ->
                            FilterChip(
                                selected = false,
                                onClick = { name = category },
                                label = { Text(category) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (mod.category != null) {
                    TextButton(onClick = { onConfirm(null) }) {
                        Text(stringResource(R.string.modpack_mod_category_clear))
                    }
                }
                TextButton(onClick = { onConfirm(trimmed.ifEmpty { null }) }) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm_cancel))
            }
        }
    )
}

@Composable
private fun ModCategoryFilterRow(
    selected: ModCategoryFilter,
    categoryNames: List<String>,
    showUncategorized: Boolean,
    onSelect: (ModCategoryFilter) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") {
            FilterChip(
                selected = selected is ModCategoryFilter.All,
                onClick = { onSelect(ModCategoryFilter.All) },
                label = { Text(stringResource(R.string.modpack_mod_category_all)) }
            )
        }
        if (showUncategorized) {
            item(key = "uncategorized") {
                FilterChip(
                    selected = selected is ModCategoryFilter.Uncategorized,
                    onClick = { onSelect(ModCategoryFilter.Uncategorized) },
                    label = { Text(stringResource(R.string.modpack_mod_category_uncategorized)) }
                )
            }
        }
        items(categoryNames, key = { it }) { category ->
            FilterChip(
                selected = selected is ModCategoryFilter.Named && selected.name == category,
                onClick = { onSelect(ModCategoryFilter.Named(category)) },
                label = { Text(category) }
            )
        }
    }
}

@Composable
private fun ModItemCard(
    mod: ModpackMod,
    onToggle: (Boolean) -> Unit,
    onRename: () -> Unit,
    onSetCategory: () -> Unit,
    onDelete: () -> Unit
) {
    val showsMappedName = mod.displayName != mod.file.name
    val enableLabel = stringResource(R.string.modpack_mod_enable)
    val subtitle = buildString {
        val category = mod.category?.takeIf { it.isNotBlank() }
        if (category != null) {
            append(category)
            append(" · ")
        }
        if (showsMappedName || mod.relativePath.contains('/')) {
            append(mod.relativePath)
            append(" · ")
        }
        append(formatFileSize(mod.file.length()))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (mod.enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (mod.enabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mod.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (mod.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = mod.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription = enableLabel + ": " + mod.displayName
                    }
                )
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
                TextButton(onClick = onSetCategory) {
                    Icon(
                        imageVector = Icons.Outlined.Category,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.modpack_mod_category))
                }
                TextButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.modpack_rename))
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
private fun ModSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val placeholder = stringResource(R.string.modpack_search_mods)
    var focused by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = 1.dp,
            color = if (focused) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
            }
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboardController?.hide() }
                ),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.modpack_search_clear),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BrowseModFilesCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.modpack_browse_mod_files),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.modpack_browse_mod_files_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun AddModActionsButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    scanning: Boolean,
    onManualImport: () -> Unit,
    onAutoScan: () -> Unit,
    onImportFromModpack: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        label = "addModMenuRotation"
    )

    Box {
        IconButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(
                    if (expanded) R.string.modpack_actions_close else R.string.modpack_add_mod
                ),
                modifier = Modifier.rotate(rotation)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            offset = DpOffset(0.dp, 0.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            tonalElevation = 2.dp
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.modpack_import_manual)) },
                onClick = {
                    onExpandedChange(false)
                    onManualImport()
                },
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
                onClick = {
                    onExpandedChange(false)
                    onAutoScan()
                },
                enabled = !scanning,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.modpack_import_from_other)) },
                onClick = {
                    onExpandedChange(false)
                    onImportFromModpack()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.DriveFileMove,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )
        }
    }
}

@Composable
private fun DownloadDllScanDialog(
    files: List<File>,
    selectedPaths: Set<String>,
    onToggle: (File, Boolean) -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.modpack_scan_dlls_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.modpack_scan_dlls_message, files.size))
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
                                    text = formatFileSize(file.length()),
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

@Composable
private fun ImportFromModpackPickerDialog(
    modpacks: List<ModpackMeta>,
    onSelect: (ModpackMeta) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modpack_import_from_other_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(modpacks, key = { it.name }) { pack ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(pack) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pack.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    R.string.modpack_mod_count_ratio,
                                    pack.enabledModCount,
                                    pack.modCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.modpack_scan_downloads_skip))
            }
        }
    )
}

@Composable
private fun ImportModsFromModpackDialog(
    sourceName: String,
    mods: List<ModpackMod>,
    selectedPaths: Set<String>,
    onToggle: (ModpackMod, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit
) {
    val selectAllState = when {
        mods.isEmpty() || selectedPaths.none { path -> mods.any { it.relativePath == path } } ->
            ToggleableState.Off
        mods.all { it.relativePath in selectedPaths } -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.modpack_import_from_other_mods_title, sourceName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.modpack_import_from_other_mods_message))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(mods, key = { it.relativePath }) { mod ->
                        val checked = mod.relativePath in selectedPaths
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(mod, !checked) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggle(mod, it) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mod.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = mod.relativePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { onSelectAll(selectAllState != ToggleableState.On) },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text(
                        text = stringResource(
                            if (selectAllState == ToggleableState.On) {
                                R.string.modpack_import_from_other_deselect_all
                            } else {
                                R.string.modpack_import_from_other_select_all
                            }
                        )
                    )
                }
                Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.modpack_scan_downloads_skip))
                    }
                    TextButton(
                        onClick = onImport,
                        enabled = selectedPaths.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.modpack_scan_downloads_import))
                    }
                }
            }
        }
    )
}
