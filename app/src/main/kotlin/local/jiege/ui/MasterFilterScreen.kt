package local.jiege.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.itos.coui.chip.CouiChipTabItem
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog
import com.itos.coui.dialog.InputDialog
import com.itos.coui.dialog.LoadingDialog
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.CouiMenuDefaults
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.theme.CouiColor
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.TopAppBarButtonGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.jiege.settings.MasterFilterStore
import local.jiege.settings.ModuleSettings

private enum class FilterChip(val label: String) {
    ALL("全部"),
    PRESET("我的预设"),
    IMPORTED("导入"),
    HIDDEN("已隐藏"),
}

private enum class MoreAction { BACKUP, RESTORE, RESET }

/** Top-bar actions for Tab 3 (滤镜): Import LUT (+) and More (⋯ -> Backup / Restore / Reset Order). */
internal class FilterScreenActions {
    var importTrigger by mutableIntStateOf(0)
    var backupTrigger by mutableIntStateOf(0)
    var restoreTrigger by mutableIntStateOf(0)
    var resetTrigger by mutableIntStateOf(0)
}

@Composable
internal fun FilterTopBarActions(actions: FilterScreenActions) {
    val moreAnchor = rememberCouiMenuAnchor()
    var menuExpanded by remember { mutableStateOf(false) }
    val dark = CouiTheme.isDark

    val buttons: @Composable RowScope.() -> Unit = {
        CouiTopIconAction(
            onClick = { actions.importTrigger++ },
            contentDescription = "导入 LUT",
        ) {
            UiIcon(imageVector = CouiIcons.Filled.Add, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.couiMenuAnchor(moreAnchor)) {
            CouiTopIconAction(
                onClick = { moreAnchor.open { menuExpanded = true } },
                contentDescription = "更多操作",
            ) {
                UiIcon(
                    imageVector = CouiIcons.Outlined.More,
                    modifier = Modifier.size(20.dp),
                )
            }
            Menu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                anchor = moreAnchor,
                style = CouiMenuDefaults.style(dark),
                items = listOf(
                    CouiMenuItem(MoreAction.BACKUP, "备份配置"),
                    CouiMenuItem(MoreAction.RESTORE, "从备份恢复"),
                    CouiMenuItem(MoreAction.RESET, "恢复默认排序", dividerBefore = true),
                ),
                onItemClick = { item ->
                    menuExpanded = false
                    when (item.value) {
                        MoreAction.BACKUP -> actions.backupTrigger++
                        MoreAction.RESTORE -> actions.restoreTrigger++
                        MoreAction.RESET -> actions.resetTrigger++
                    }
                },
            )
        }
    }

    if (Build.VERSION.SDK_INT >= 34) {
        TopAppBarButtonGroup { buttons() }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
    }
}

/** Tab 3 (滤镜): Segment 0 (Filter List + Drag Reorder + Chip Tabs), Segment 1 (Optics & Presets). */
@Composable
internal fun MasterFilterScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    segment: Int = 0,
    onSegmentChange: (Int) -> Unit = {},
    chipOrdinal: Int = 0,
    onChipOrdinalChange: (Int) -> Unit = {},
    settings: SettingsStore,
    actions: FilterScreenActions,
    onOpenSoftLight: () -> Unit,
    onOpenOpticalVignette: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { MasterFilters.store(context) }
    val scope = rememberCoroutineScope()
    val rows = remember(store) {
        mutableStateListOf<MasterFilterStore.Row>().apply { addAll(store.rows()) }
    }
    var hasCamera by remember(store) { mutableStateOf(store.hasCameraList()) }
    var safBusyMessage by remember { mutableStateOf<String?>(null) }
    var lastSafMessage by remember { mutableStateOf("正在处理…") }
    safBusyMessage?.let { lastSafMessage = it }

    var pendingRename by remember { mutableStateOf<MasterFilterStore.Row?>(null) }
    var renameVisible by remember { mutableStateOf(false) }
    var renameSession by remember { mutableIntStateOf(0) }
    var renameValue by remember { mutableStateOf(TextFieldValue("")) }
    var pendingDelete by remember { mutableStateOf<MasterFilterStore.Row?>(null) }
    var deleteVisible by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetEverOpened by remember { mutableStateOf(false) }
    if (confirmReset) resetEverOpened = true

    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openRename(row: MasterFilterStore.Row) {
        pendingRename = row
        renameValue = TextFieldValue(row.title, TextRange(row.title.length))
        renameSession++
        renameVisible = true
    }

    val chip = FilterChip.entries.getOrElse(chipOrdinal) { FilterChip.ALL }
    val busy = safBusyMessage != null

    suspend fun reloadLocal() {
        val fresh = withContext(Dispatchers.IO) {
            store.rows() to store.hasCameraList()
        }
        rows.clear()
        rows.addAll(fresh.first)
        hasCamera = fresh.second
    }

    fun mutateLocal(work: () -> Unit) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    work()
                    AppRestart.stopCamera()
                }
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "操作失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            reloadLocal()
        }
    }

    fun runSaf(message: String, restartCamera: Boolean, work: () -> String) {
        if (safBusyMessage != null) return
        safBusyMessage = message
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val outcome = work()
                    if (restartCamera) AppRestart.stopCamera()
                    outcome
                }
            }
            safBusyMessage = null
            val text = result.getOrElse { error ->
                "操作失败：${error.message ?: error.javaClass.simpleName}"
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            if (restartCamera) reloadLocal()
        }
    }

    LaunchedEffect(Unit) {
        val fresh = withContext(Dispatchers.IO) {
            runCatching { CameraReport.fetch(context) }
            store.rows() to store.hasCameraList()
        }
        rows.clear()
        rows.addAll(fresh.first)
        hasCamera = fresh.second
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        runSaf("正在导入 LUT…", restartCamera = true) {
            val header = MasterFilters.header(context)
            val failed = mutableListOf<String>()
            var done = 0
            for (uri in uris) {
                val name = displayName(context, uri)
                try {
                    context.contentResolver.openInputStream(uri)!!.use { store.importCube(name, it, header) }
                    done++
                } catch (error: Throwable) {
                    failed += "$name（${error.message ?: error.javaClass.simpleName}）"
                }
            }
            buildString {
                append("已导入 $done 个 LUT")
                if (failed.isNotEmpty()) append("；${failed.size} 个失败：${failed.joinToString("、")}")
            }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runSaf("正在备份配置…", restartCamera = false) {
            context.contentResolver.openOutputStream(uri)!!.use { store.backup(it) }
            "已备份配置，包含导入的 LUT"
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runSaf("正在从备份恢复…", restartCamera = true) {
            context.contentResolver.openInputStream(uri)!!.use { store.restore(it) }
            "已从备份恢复"
        }
    }

    LaunchedEffect(actions.importTrigger) {
        if (actions.importTrigger > 0) {
            actions.importTrigger = 0
            if (!busy) importLauncher.launch(arrayOf("*/*"))
        }
    }
    LaunchedEffect(actions.backupTrigger) {
        if (actions.backupTrigger > 0) {
            actions.backupTrigger = 0
            if (!busy) backupLauncher.launch("大师滤镜备份.zip")
        }
    }
    LaunchedEffect(actions.restoreTrigger) {
        if (actions.restoreTrigger > 0) {
            actions.restoreTrigger = 0
            if (!busy) restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
    }
    LaunchedEffect(actions.resetTrigger) {
        if (actions.resetTrigger > 0) {
            actions.resetTrigger = 0
            if (!busy) confirmReset = true
        }
    }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var activeDragPointerId by remember { mutableStateOf<PointerId?>(null) }
    var dragStart by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(draggingId) {
        val activeId = draggingId ?: return@LaunchedEffect
        val edgePx = with(density) { 88.dp.toPx() }
        val maxStepPx = with(density) { 16.dp.toPx() }
        while (draggingId == activeId) {
            val info = listState.layoutInfo
            val current = info.visibleItemsInfo.firstOrNull { it.key == activeId }
            val draggedRow = rows.firstOrNull { it.id == activeId }
            if (current != null && draggedRow != null) {
                val topEdge = current.offset + dragOffset
                val bottomEdge = current.offset + current.size + dragOffset
                val topZone = info.viewportStartOffset + info.beforeContentPadding + edgePx
                val bottomZone = info.viewportEndOffset - info.afterContentPadding - edgePx
                val scrollStep = when {
                    topEdge < topZone -> {
                        val ratio = ((topZone - topEdge) / edgePx).coerceIn(0.15f, 1.8f)
                        -maxStepPx * ratio
                    }
                    bottomEdge > bottomZone -> {
                        val ratio = ((bottomEdge - bottomZone) / edgePx).coerceIn(0.15f, 1.8f)
                        maxStepPx * ratio
                    }
                    else -> 0f
                }
                if (scrollStep != 0f) {
                    val consumed = listState.scrollBy(scrollStep)
                    if (consumed != 0f) {
                        // 列表滚动后补偿位移，使被拖动项始终紧跟手指
                        dragOffset += consumed
                    }
                }
                val swapped = swapUnderDrag(listState, rows, draggedRow, dragOffset)
                if (swapped != 0f) {
                    dragOffset -= swapped
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            delay(16L)
        }
    }

    val shown = rows.filter { it.visible }
    val hidden = rows.filter { !it.visible }
    val filteredRows = when (chip) {
        FilterChip.ALL -> emptyList()
        FilterChip.PRESET -> rows.filter { it.preset }
        FilterChip.IMPORTED -> rows.filter { it.imported }
        FilterChip.HIDDEN -> hidden
    }

    LazyColumn(
        state = listState,
        userScrollEnabled = draggingId == null,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 双手排序支持：一手按住数据项时，另一只手可在列表任意位置上下滑动
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val activeId = draggingId ?: continue
                        val dragPid = activeDragPointerId ?: continue
                        val other = event.changes.firstOrNull { it.id != dragPid && it.pressed } ?: continue
                        val dy = other.position.y - other.previousPosition.y
                        if (dy != 0f) {
                            other.consume()
                            val consumed = listState.dispatchRawDelta(-dy)
                            if (consumed != 0f) {
                                dragOffset += consumed
                                val draggedRow = rows.firstOrNull { it.id == activeId }
                                if (draggedRow != null) {
                                    val swapped = swapUnderDrag(listState, rows, draggedRow, dragOffset)
                                    if (swapped != 0f) {
                                        dragOffset -= swapped
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "segment_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.horizontalMargin)
                    .padding(top = 12.dp, bottom = 4.dp),
            ) {
                CouiSegmentedBar(
                    labels = listOf("滤镜列表", "光效与预设"),
                    selectedIndex = segment,
                    onSelected = {
                        draggingId = null
                        activeDragPointerId = null
                        dragOffset = 0f
                        onSegmentChange(it)
                    },
                )
            }
        }

        if (segment == 0) {
            if (!hasCamera) {
                item(key = "no_camera_hint") {
                    UiGroup {
                        UiInfoRow(
                            title = "尚未读取到相机滤镜列表",
                            summary = "打开一次相机即可自动同步",
                        )
                    }
                }
            }
            item(key = "filter_chips") {
                Spacer(Modifier.height(10.dp))
                CouiChipFilterBar(
                    items = FilterChip.entries.map { CouiChipTabItem(key = it, label = it.label) },
                    selectedKey = chip,
                    onSelectedKeyChange = {
                        draggingId = null
                        activeDragPointerId = null
                        dragOffset = 0f
                        onChipOrdinalChange((it as FilterChip).ordinal)
                    },
                )
            }

            if (chip == FilterChip.ALL) {
                item(key = "shown_title") {
                    UiText(
                        text = "显示中（${shown.size}）· 按住右侧拖动",
                        kind = CouiTextKind.GroupTitle,
                        color = CouiTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
                itemsIndexed(shown, key = { _, r -> r.id }) { idx, row ->
                    FilterRow(
                        row = row,
                        shape = groupShape(shown, row),
                        showDivider = idx < shown.lastIndex,
                        dragging = draggingId == row.id,
                        dragOffset = dragOffset,
                        busy = busy,
                        draggable = true,
                        dragModifier = dragModifier(
                            row = row,
                            busy = busy,
                            onStart = { pointerId ->
                                draggingId = row.id
                                activeDragPointerId = pointerId
                                dragStart = rows.indexOfFirst { it.id == row.id }
                                dragOffset = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { deltaY ->
                                dragOffset += deltaY
                                val swapped = swapUnderDrag(listState, rows, row, dragOffset)
                                if (swapped != 0f) {
                                    dragOffset -= swapped
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onEnd = {
                                val from = dragStart
                                val to = rows.indexOfFirst { it.id == row.id }
                                draggingId = null
                                activeDragPointerId = null
                                dragOffset = 0f
                                if (from >= 0 && to >= 0 && from != to) {
                                    mutateLocal { store.move(from, to) }
                                }
                            },
                            onCancel = {
                                draggingId = null
                                activeDragPointerId = null
                                dragOffset = 0f
                                scope.launch { reloadLocal() }
                            },
                        ),
                        onToggle = { checked -> mutateLocal { store.setVisible(row.id, checked) } },
                        onRename = { openRename(row) },
                        onDelete = {
                            pendingDelete = row
                            deleteVisible = true
                        },
                    )
                }
                if (hidden.isNotEmpty()) {
                    item(key = "hidden_title") {
                        Spacer(Modifier.height(8.dp))
                        UiText(
                            text = "已隐藏（${hidden.size}）",
                            kind = CouiTextKind.GroupTitle,
                            color = CouiTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(hidden, key = { _, r -> r.id }) { idx, row ->
                        FilterRow(
                            row = row,
                            shape = groupShape(hidden, row),
                            showDivider = idx < hidden.lastIndex,
                            dragging = false,
                            dragOffset = 0f,
                            busy = busy,
                            draggable = false,
                            dragModifier = Modifier,
                            onToggle = { checked -> mutateLocal { store.setVisible(row.id, checked) } },
                            onRename = { openRename(row) },
                            onDelete = {
                                pendingDelete = row
                                deleteVisible = true
                            },
                        )
                    }
                    item(key = "hidden_footer") {
                        UiText(
                            text = "不影响基于该滤镜保存的预设，预设可单独隐藏。",
                            kind = CouiTextKind.GroupFooter,
                            color = CouiTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        )
                    }
                }
            } else {
                if (filteredRows.isEmpty()) {
                    item(key = "empty_filter") {
                        UiGroup {
                            UiInfoRow(
                                title = "暂无${chip.label}",
                                summary = when (chip) {
                                    FilterChip.IMPORTED -> "点右上角 + 导入 Rec.709 .cube 文件"
                                    FilterChip.PRESET -> "相机内保存的预设会自动同步至此"
                                    else -> "当前没有隐藏的滤镜"
                                },
                            )
                        }
                    }
                } else {
                    item(key = "filtered_header") {
                        Spacer(Modifier.height(6.dp))
                    }
                    itemsIndexed(filteredRows, key = { _, r -> r.id }) { idx, row ->
                        FilterRow(
                            row = row,
                            shape = groupShape(filteredRows, row),
                            showDivider = idx < filteredRows.lastIndex,
                            dragging = false,
                            dragOffset = 0f,
                            busy = busy,
                            draggable = false,
                            dragModifier = Modifier,
                            onToggle = { checked -> mutateLocal { store.setVisible(row.id, checked) } },
                            onRename = { openRename(row) },
                            onDelete = {
                                pendingDelete = row
                                deleteVisible = true
                            },
                        )
                    }
                }
            }
            item(key = "bottom_space_0") {
                Spacer(Modifier.height(16.dp))
            }
        } else {
            item(key = "optics_and_presets") {
                var portraOn by remember { mutableStateOf(settings.get(ModuleSettings.MASTER_EFFECT_PORTRA)) }
                var portraPhysicalOn by remember { mutableStateOf(settings.get(ModuleSettings.MASTER_PORTRA_PHYSICAL)) }
                val softLightText = remember(settings, resumeTick) { softLightSummary(settings) }
                val opticalVignetteText = remember(settings, resumeTick) { opticalVignetteSummary(settings) }
                UiGroup(title = "光学与胶片光效") {
                    CouiNavRow(
                        title = "光学柔光模拟",
                        summary = softLightText,
                        onClick = onOpenSoftLight,
                    )
                    UiDivider()
                    CouiNavRow(
                        title = "光学暗角模拟",
                        summary = opticalVignetteText,
                        onClick = onOpenOpticalVignette,
                    )
                    UiDivider()
                    CouiSettingSwitch(
                        store = settings,
                        key = ModuleSettings.MASTER_EFFECT_NEON,
                        title = "霓虹",
                        summary = "叠加 800T 光晕与颗粒",
                    )
                    UiDivider()
                    CouiSettingSwitch(
                        store = settings,
                        key = ModuleSettings.MASTER_EFFECT_PORTRA,
                        title = "和光",
                        summary = "叠加 Portra 高光溢光，保留大师颗粒设置",
                        onChange = { portraOn = it },
                    )
                    AnimatedVisibility(visible = portraOn || portraPhysicalOn) {
                        Column {
                            UiDivider()
                            CouiSettingSwitch(
                                store = settings,
                                key = ModuleSettings.MASTER_PORTRA_PHYSICAL,
                                title = "和光物理胶片",
                                summary = "按 Portra 400 实测曲线模拟散射与光晕，仅作用于成片",
                                onChange = { portraPhysicalOn = it },
                            )
                        }
                    }
                }
                UiGroup(title = "预设与跨模式") {
                    CouiSettingSwitch(
                        store = settings,
                        key = ModuleSettings.MASTER_PRO_EXPAND,
                        title = "专业预设展开 Pro 面板",
                        summary = "选中带参数预设时自动切入 Pro 挡",
                    )
                    UiDivider()
                    CouiSettingSwitch(
                        store = settings,
                        key = ModuleSettings.MASTER_PRO_LABEL,
                        title = "在滤镜名称卡片进行提示",
                        summary = "已记忆参数的滤镜与预设显示 Pro Preset",
                    )
                    UiDivider()
                    CouiSettingSwitch(
                        store = settings,
                        key = ModuleSettings.NORMAL_FOLLOWS_MASTER,
                        title = "拍照模式同步大师滤镜列表",
                        summary = "拍照、人像与哈苏超清共用大师滤镜顺序、显隐及扩展滤镜",
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorScheme
    val (material, backdrop) = rememberOmniDialogMaterial(dark)

    if (resetEverOpened) {
        Dialog(
            onDismissRequest = { confirmReset = false },
            title = "恢复默认排序",
            message = "预设排在最前，显隐状态不变。",
            dark = dark,
            material = material,
            backdrop = backdrop,
            visible = confirmReset,
            confirmAction = CouiDialogAction(
                text = "恢复",
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogDefaults.primaryActionColors(scheme.accent),
                onClick = {
                    confirmReset = false
                    mutateLocal { store.resetOrder() }
                },
            ),
            dismissAction = CouiDialogAction(
                text = "取消",
                onClick = { confirmReset = false },
            ),
        )
    }

    pendingRename?.let { row ->
        key(renameSession) {
            val maxLen = 32
            val sanitized = renameValue.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
            val tooLong = renameValue.text.length > maxLen
            val canSave = if (row.imported) sanitized.isNotEmpty() && !tooLong else !tooLong
            InputDialog(
                value = renameValue,
                onValueChange = { renameValue = it },
                onDismissRequest = { renameVisible = false },
                title = "重命名滤镜",
                placeholder = if (row.imported) "输入滤镜名称" else "留空则恢复默认（${row.defaultTitle}）",
                clearContentDescription = "清空",
                counter = "${renameValue.text.length}/$maxLen",
                counterIsError = tooLong,
                dark = dark,
                accent = scheme.accent,
                material = material,
                backdrop = backdrop,
                visible = renameVisible,
                confirmAction = CouiDialogAction(
                    text = "保存",
                    enabled = canSave,
                    style = CouiDialogActionStyle.Primary,
                    colors = CouiDialogDefaults.primaryActionColors(scheme.accent),
                    onClick = {
                        renameVisible = false
                        val target = if (!row.imported && sanitized == row.defaultTitle) "" else sanitized
                        if (sanitized != row.title || (!row.imported && target.isEmpty() && row.renamed)) {
                            mutateLocal { store.rename(row.id, target) }
                        }
                    },
                ),
                dismissAction = CouiDialogAction(
                    text = "取消",
                    onClick = { renameVisible = false },
                ),
            )
        }
    }

    pendingDelete?.let { row ->
        Dialog(
            onDismissRequest = { deleteVisible = false },
            title = "删除导入的滤镜",
            message = "删除“${row.title}”？关联的相机预设将一并移除。",
            dark = dark,
            material = material,
            backdrop = backdrop,
            visible = deleteVisible,
            dismissAction = CouiDialogAction(
                text = "取消",
                onClick = { deleteVisible = false },
            ),
            confirmAction = CouiDialogAction(
                text = "删除",
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogDefaults.primaryActionColors(CouiDialogDefaults.colors(dark).destructive),
                onClick = {
                    deleteVisible = false
                    mutateLocal { store.deleteImported(row.id) }
                },
            ),
        )
    }

    LoadingDialog(
        visible = safBusyMessage != null,
        message = lastSafMessage,
        dark = dark,
        material = material,
        backdrop = backdrop,
    )
}

@Composable
private fun FilterRow(
    row: MasterFilterStore.Row,
    shape: RoundedCornerShape,
    showDivider: Boolean,
    dragging: Boolean,
    dragOffset: Float,
    busy: Boolean,
    draggable: Boolean,
    dragModifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onRename: () -> Unit = {},
    onDelete: () -> Unit,
) {
    val scheme = CouiTheme.colorScheme
    val dark = CouiTheme.isDark
    val bgColor = if (dragging) {
        if (dark) Color(0x33FFFFFF) else Color(0xFFF5F6F8)
    } else {
        scheme.card
    }
    Column(
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
            .padding(horizontal = CouiPageDefaults.horizontalMargin)
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                UiText(
                    text = row.title,
                    kind = CouiTextKind.ItemTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                UiText(
                    text = row.category,
                    kind = CouiTextKind.ItemSummary,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!row.preset) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !busy, role = Role.Button, onClick = onRename),
                    contentAlignment = Alignment.Center,
                ) {
                    UiIcon(
                        imageVector = EditIcon,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                        contentDescription = "重命名",
                    )
                }
                Spacer(Modifier.width(if (row.imported) 2.dp else 4.dp))
            }
            if (row.imported) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !busy, role = Role.Button, onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    UiIcon(
                        imageVector = DeleteIcon,
                        tint = if (dark) CouiColor.Night.RED else CouiColor.Day.RED,
                        modifier = Modifier.size(18.dp),
                        contentDescription = "删除",
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            if (row.fixed) {
                UiText(
                    text = "常显",
                    kind = CouiTextKind.ItemValue,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
            } else {
                Switch(
                    checked = row.visible,
                    enabled = !busy,
                    onCheckedChange = onToggle,
                )
            }
            if (draggable) {
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .then(dragModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    UiIcon(
                        imageVector = DragHandleIcon,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                        contentDescription = "按住拖动排序",
                    )
                }
            }
        }
        if (showDivider && !dragging) {
            UiDivider()
        }
    }
}

private val Corner = 24.dp

private fun groupShape(group: List<MasterFilterStore.Row>, row: MasterFilterStore.Row): RoundedCornerShape {
    val first = group.firstOrNull()?.id == row.id
    val last = group.lastOrNull()?.id == row.id
    return RoundedCornerShape(
        topStart = if (first) Corner else 0.dp,
        topEnd = if (first) Corner else 0.dp,
        bottomStart = if (last) Corner else 0.dp,
        bottomEnd = if (last) Corner else 0.dp,
    )
}

private fun dragModifier(
    row: MasterFilterStore.Row,
    busy: Boolean,
    onStart: (PointerId) -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    if (busy) return Modifier
    return Modifier.pointerInput(row.id, row.visible) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            onStart(down.id)
            var finished = false
            try {
                finished = drag(down.id) { change ->
                    val dy = change.positionChange().y
                    if (dy != 0f) {
                        change.consume()
                        onDrag(dy)
                    }
                }
            } finally {
                if (finished) onEnd() else onCancel()
            }
        }
    }
}

private fun swapUnderDrag(
    state: LazyListState,
    rows: MutableList<MasterFilterStore.Row>,
    dragged: MasterFilterStore.Row,
    offset: Float,
): Float {
    val items = state.layoutInfo.visibleItemsInfo
    val current = items.firstOrNull { it.key == dragged.id } ?: return 0f
    val centre = current.offset + current.size / 2f + offset
    val target = items.firstOrNull { item ->
        item.key != dragged.id && rows.any { it.id == item.key && it.visible == dragged.visible } &&
            centre >= item.offset && centre <= item.offset + item.size
    } ?: return 0f
    val from = rows.indexOfFirst { it.id == dragged.id }
    val to = rows.indexOfFirst { it.id == target.key }
    if (from < 0 || to < 0) return 0f
    val firstIndex = state.firstVisibleItemIndex
    val firstOffset = state.firstVisibleItemScrollOffset
    val touchesFirst = current.index == firstIndex || target.index == firstIndex
    rows.add(to, rows.removeAt(from))
    if (touchesFirst) {
        // 锚定首项滚动位置，防止交换涉及首项时 LazyColumn 视口跳动
        state.requestScrollToItem(firstIndex, firstOffset)
    }
    return (target.offset - current.offset).toFloat()
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
    }
    return uri.lastPathSegment ?: "LUT"
}
