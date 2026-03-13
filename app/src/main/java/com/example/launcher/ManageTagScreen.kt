package com.example.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

const val LEFT_PAD = 8

private data class ManageRow(
    val rowId: Long,
    // If a LauncherItem has an id you could remove this id.
    val item: LauncherItem
)

private sealed interface SelectionKey {
    data class App(val pkg: String) : SelectionKey
    data class Shortcut(val pkg: String, val id: String) : SelectionKey
    data class Tag(val id: Long) : SelectionKey
}

private fun selectionKey(item: LauncherItem): SelectionKey? = when (item) {
    is LauncherItem.App -> SelectionKey.App(item.info.componentName.packageName)
    is LauncherItem.Shortcut -> SelectionKey.Shortcut(item.info.`package`, item.info.id)
    is LauncherItem.Tag -> SelectionKey.Tag(item.id)
    is LauncherItem.Placeholder -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ManageTagScreen(
    tag: LauncherItem.Tag,
    items: List<LauncherItem>,
    appsVM: AppsVM, // should be both removed by callback
    viewVM: ViewVM
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    LaunchedEffect(tag.id, items) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            appsVM.syncTagItemsToList(tag.id, items)
        }
    }

    var localRows by remember(items) {
        mutableStateOf<List<ManageRow>>(items.mapIndexed { index, item ->
            ManageRow(
                index.toLong(),
                item
            )
        })
    }
    var draggedRowId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var settleTargetId by remember { mutableStateOf<Long?>(null) }
    val settleAnim = remember { Animatable(0f) }
    var dragStartOrder by remember { mutableStateOf<List<Long>?>(null) }
    var dragMoved by remember { mutableStateOf(false) }

    var lastPersistedOrder by remember(items) {
        mutableStateOf(items.mapNotNull { selectionKey(it) })
    }

    val persistOrder: (List<ManageRow>) -> Unit = { rows ->
        lastPersistedOrder = rows.mapNotNull { selectionKey(it.item) }
        scope.launch(Dispatchers.IO) { appsVM.updateOrder(tag.id, rows.map { it.item }) }
    }

    val finishManage: () -> Unit = {
        val currentKeys = localRows.mapNotNull { selectionKey(it.item) }
        val hasUnknown = localRows.any { selectionKey(it.item) == null }
        if (hasUnknown || currentKeys != lastPersistedOrder) {
            persistOrder(localRows)
        }
        viewVM.setView(View.Favorites)
    }

    BackHandler { finishManage() }

    val finishDrag: (Long) -> Unit = { rowId ->
        if (draggedRowId == rowId) {
            if (dragMoved) {
                val startOrder = dragStartOrder
                val finalOrder = localRows.map { it.rowId }
                if (startOrder != null && finalOrder != startOrder) {
                    persistOrder(localRows)
                }
            }

            draggedRowId = null
            dragStartOrder = null
            settleTargetId = rowId
            dragMoved = false
            scope.launch {
                settleAnim.snapTo(dragOffsetY)
                settleAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 160)
                )
                if (settleTargetId == rowId) {
                    settleTargetId = null
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = (H_PAD2 - LEFT_PAD).dp)
                .padding(bottom = 1f / 8f * screenHeight), // Should not cut of the drag. Can we make this padding be there without the cut off?
            userScrollEnabled = false,
            reverseLayout = true,
            verticalArrangement = Arrangement.Bottom
        ) {
            itemsIndexed(
                items = localRows,
                key = { _, row -> row.rowId }
            ) { _, row ->
                val rowId = row.rowId
                val isDragged = draggedRowId == rowId
                val shouldSettle = settleTargetId == rowId
                var rowWidthPx by remember(rowId) { mutableFloatStateOf(1f) }
                var thresholdArmed by remember(rowId) { mutableStateOf(false) }
                var reachedThreshold by remember(rowId) { mutableStateOf(false) }
                val dismissThresholdFraction = 0.30f

                // Seems overkill
                val dismissState = rememberSwipeToDismissBoxState(
                    positionalThreshold = { totalDistance -> totalDistance * dismissThresholdFraction },
                    confirmValueChange = { value ->
                        if (draggedRowId != null) return@rememberSwipeToDismissBoxState false
                        when (value) {
                            SwipeToDismissBoxValue.StartToEnd,
                            SwipeToDismissBoxValue.EndToStart -> {
                                if (!reachedThreshold) return@rememberSwipeToDismissBoxState false
                                val updated = localRows.filterNot { it.rowId == rowId }
                                localRows = updated
                                persistOrder(updated)
                                true
                            }

                            SwipeToDismissBoxValue.Settled -> true
                        }
                    }
                )

                val dismissDirection = dismissState.dismissDirection
                val dismissOffset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
                val showSwipe =
                    abs(dismissOffset) > 1f ||
                            dismissDirection != SwipeToDismissBoxValue.Settled

                LaunchedEffect(dismissOffset, rowWidthPx) {
                    reachedThreshold = abs(dismissOffset) >= rowWidthPx * dismissThresholdFraction
                }

                LaunchedEffect(dismissDirection, reachedThreshold) {
                    if (dismissDirection == SwipeToDismissBoxValue.Settled) {
                        thresholdArmed = false
                    } else if (reachedThreshold && !thresholdArmed) {
                        thresholdArmed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else if (!reachedThreshold) {
                        thresholdArmed = false
                    }
                }

                val interactionActive = isDragged || showSwipe
                val rowBackgroundColor by animateColorAsState(
                    targetValue = if (interactionActive) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(durationMillis = 140),
                    label = "RowBackgroundFade"
                )

                SwipeToDismissBox(
                    modifier = Modifier
                        .zIndex(if (isDragged) 10f else 0f)
                        .then(if (isDragged || shouldSettle) Modifier else Modifier.animateItemPlacement()),
                    state = dismissState,
                    enableDismissFromStartToEnd = draggedRowId == null,
                    enableDismissFromEndToStart = draggedRowId == null,
                    backgroundContent = {
                        if (showSwipe) {
                            val align = if (dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                Alignment.CenterStart
                            } else {
                                Alignment.CenterEnd
                            }
                            // Why box?
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 8.dp, end = H_PAD.dp),
                                contentAlignment = align
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = if (reachedThreshold) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { rowWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                            .graphicsLayer {
                                if (isDragged) {
                                    translationY = dragOffsetY
                                } else if (shouldSettle) {
                                    translationY = settleAnim.value
                                }
                            }
                            .pointerInput(rowId) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        draggedRowId = rowId
                                        settleTargetId = null
                                        dragStartOrder = localRows.map { it.rowId }
                                        scope.launch {
                                            settleAnim.stop()
                                            settleAnim.snapTo(0f)
                                        }
                                        dragOffsetY = 0f
                                        dragMoved = false
                                    },
                                    onDragEnd = { finishDrag(rowId) },
                                    onDragCancel = { finishDrag(rowId) },
                                    onVerticalDrag = { change, dragAmount ->
                                        if (draggedRowId != rowId) return@detectVerticalDragGestures
                                        change.consume()
                                        dragOffsetY += dragAmount

                                        val draggingInfo = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == rowId }
                                            ?: return@detectVerticalDragGestures

                                        val rowSpan = draggingInfo.size.toFloat().coerceAtLeast(1f)
                                        val halfSpan = rowSpan / 2f
                                        val fromIndex = localRows.indexOfFirst { it.rowId == rowId }
                                        if (fromIndex < 0) return@detectVerticalDragGestures

                                        val toIndex = when {
                                            dragOffsetY <= -halfSpan && fromIndex < localRows.lastIndex -> fromIndex + 1
                                            dragOffsetY >= halfSpan && fromIndex > 0 -> fromIndex - 1
                                            else -> -1
                                        }
                                        if (toIndex >= 0) {
                                            val reordered = localRows.toMutableList()
                                            reordered.add(toIndex, reordered.removeAt(fromIndex))
                                            localRows = reordered

                                            // Rebase offset after swapping index to avoid immediate back-swap jitter.
                                            dragOffsetY += if (toIndex > fromIndex) rowSpan else -rowSpan
                                            dragMoved = true
                                        }
                                    }
                                )
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large) // what is clip
                                .background(rowBackgroundColor)
                                .padding(start = LEFT_PAD.dp)
                        ) {
                            // Why box?
                            Box(modifier = Modifier.weight(1f)) {
                                LauncherRowLayout(item = row.item)
                            }
                            Icon(
                                painter = painterResource(id = R.drawable.drag_indicator_24dp_e3e3e3_fill0_wght400_grad0_opsz24),
                                contentDescription = "Reorder",
                                modifier = Modifier
                                    .padding(horizontal = H_PAD.dp)
                                    .size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
            item(key = "add-row") {
                AddItemsRow(
                    onClick = {
                        viewVM.setView(
                            View.ManageTagAdd(
                                tag,
                                localRows.map { it.item }
                            )
                        )
                    }
                )
            }
        }

        FloatingActionButton(
            onClick = finishManage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Check, contentDescription = "Confirm")
        }
    }
}

@Composable
private fun AddItemsRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(start = LEFT_PAD.dp, end = H_PAD.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add items",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = "Add items\u2026",
            modifier = Modifier.padding(start = H_PAD.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private data class SelectorPopupState(
    val app: LauncherItem.App,
    val entries: List<LauncherItem.Shortcut>,
    val yPos: Float
)

@Composable
fun ManageTagAddScreen(
    tag: LauncherItem.Tag,
    items: List<LauncherItem>,
    appsVM: AppsVM,
    viewVM: ViewVM
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val tags by produceState(initialValue = emptyList<LauncherItem.Tag>(), tag.id) {
        value = appsVM.allTags.first()
    }
    val allApps by produceState(initialValue = emptyMap<Char, List<LauncherItem.App>>(), tag.id) {
        val grouped = appsVM.uiAllGrouped.first()
        value = grouped.mapValues { (_, list) ->
            list.filterIsInstance<LauncherItem.App>()
        }.filterValues { it.isNotEmpty() }
    }

    val initialKeys = remember(items) {
        items.mapNotNull { selectionKey(it) }.toSet()
    }
    var selectedMap by remember(items) {
        mutableStateOf(items.mapNotNull { item ->
            val key = selectionKey(item) ?: return@mapNotNull null
            key to item
        }.toMap())
    }

    var popupState by remember { mutableStateOf<SelectorPopupState?>(null) }
    var shortcutCache by remember { mutableStateOf<Map<String, List<LauncherItem.Shortcut>>>(emptyMap()) }

    val toggle: (LauncherItem) -> Unit = toggle@{ item ->
        val key = selectionKey(item) ?: return@toggle
        selectedMap = if (selectedMap.containsKey(key)) {
            selectedMap - key
        } else {
            selectedMap + (key to item)
        }
    }

    val confirm: () -> Unit = {
        val retained = items.filter { item ->
            val key = selectionKey(item) ?: return@filter false
            selectedMap.containsKey(key)
        }

        val orderedAdditions = buildList {
            tags.forEach { tagItem ->
                val key = SelectionKey.Tag(tagItem.id)
                if (!initialKeys.contains(key) && selectedMap.containsKey(key)) add(tagItem)
            }
            allApps.toSortedMap().forEach { (_, list) ->
                list.forEach { appItem ->
                    val key = SelectionKey.App(appItem.info.componentName.packageName)
                    if (!initialKeys.contains(key) && selectedMap.containsKey(key)) add(appItem)
                }
            }
            allApps.toSortedMap().forEach { (_, list) ->
                list.forEach { appItem ->
                    val shortcuts = shortcutCache[appItem.info.componentName.packageName].orEmpty()
                    shortcuts.forEach { shortcut ->
                        val key = SelectionKey.Shortcut(shortcut.info.`package`, shortcut.info.id)
                        if (!initialKeys.contains(key) && selectedMap.containsKey(key)) add(shortcut)
                    }
                }
            }
        }

        viewVM.setView(View.ManageTag(tag, retained + orderedAdditions))
    }

    BackHandler { confirm() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = (H_PAD2 - LEFT_PAD).dp),
            contentPadding = PaddingValues(
                top = 1f / 3f * screenHeight,
                bottom = 2f / 3f * screenHeight
            )
        ) {
            item(key = "tag-header") {
                SelectorHeader("#")
            }
            items(
                items = tags,
                key = { it.id }
            ) { tagItem ->
                TagSelectorRow(
                    tagItem = tagItem,
                    selected = selectedMap.containsKey(SelectionKey.Tag(tagItem.id)),
                    onToggle = { toggle(tagItem) }
                )
            }
            if (tags.isNotEmpty()) {
                item(key = "tag-spacer") { Spacer(modifier = Modifier.height(48.dp)) }
            }
            allApps.toSortedMap().forEach { (letter, list) ->
                item(key = "header-$letter") {
                    SelectorHeader(letter.toString())
                }
                items(
                    items = list,
                    key = { it.info.componentName.packageName }
                ) { appItem ->
                    AppSelectorRow(
                        appItem = appItem,
                        selected = selectedMap.containsKey(
                            SelectionKey.App(appItem.info.componentName.packageName)
                        ),
                        onToggle = { toggle(appItem) },
                        onOpenPopup = { yPos ->
                            scope.launch {
                                val entries = appsVM.popupEntriesSnapshot(appItem)
                                    .filterIsInstance<LauncherItem.Shortcut>()
                                if (entries.isEmpty()) return@launch
                                shortcutCache = shortcutCache + (appItem.info.componentName.packageName to entries)
                                popupState = SelectorPopupState(appItem, entries, yPos)
                            }
                        }
                    )
                }
                item(key = "spacer-$letter") { Spacer(modifier = Modifier.height(48.dp)) }
            }
        }

        SelectorLetterBar(
            tags = tags,
            allApps = allApps,
            listState = listState,
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        FloatingActionButton(
            onClick = confirm,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Check, contentDescription = "Confirm")
        }

        popupState?.let { popup ->
            SelectorShortcutPopup(
                popup = popup,
                selectedMap = selectedMap,
                onToggle = toggle,
                onDismiss = { popupState = null }
            )
        }
    }
}

@Composable
private fun SelectorHeader(text: String) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TagSelectorRow(
    tagItem: LauncherItem.Tag,
    selected: Boolean,
    onToggle: () -> Unit
) {
    SelectorRowLayout(
        leading = {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "#", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        },
        label = tagItem.name,
        selected = selected,
        modifier = Modifier.pointerInput(tagItem) {
            detectTapGestures { onToggle() }
        }
    )
}

@Composable
private fun AppSelectorRow(
    appItem: LauncherItem.App,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpenPopup: (Float) -> Unit
) {
    var layoutCoordinates: LayoutCoordinates? = null
    var fired by remember { mutableStateOf(false) }
    SelectorRowLayout(
        leading = { RowIcon(appItem.icon) },
        label = appItem.label,
        selected = selected,
        modifier = Modifier
            .onGloballyPositioned { coordinates -> layoutCoordinates = coordinates }
            .pointerInput(appItem) {
                detectTapGestures { onToggle() }
            }
            .pointerInput(appItem) {
                detectHorizontalDragGestures(onDragStart = { fired = false }) { change, drag ->
                    if (fired) return@detectHorizontalDragGestures
                    if (drag > 50f) {
                        layoutCoordinates?.boundsInWindow()?.bottom?.let { y ->
                            fired = true
                            change.consume()
                            onOpenPopup(y)
                        }
                    }
                }
            }
    )
}

@Composable
private fun SelectorRowLayout(
    leading: @Composable () -> Unit,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val indent by animateDpAsState(
        targetValue = if (selected) 12.dp else 0.dp,
        animationSpec = tween(durationMillis = 140),
        label = "SelectorIndent"
    )
    val rowBackgroundColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 140),
        label = "SelectorRowBackground"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(H_PAD.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(H_PAD.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackgroundColor, MaterialTheme.shapes.large)
                .padding(start = indent)
                .padding(vertical = 8.dp)
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            leading()
            RowLabel(label)
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SelectorShortcutPopup(
    popup: SelectorPopupState,
    selectedMap: Map<SelectionKey, LauncherItem>,
    onToggle: (LauncherItem) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val safeTopDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val yDp = with(LocalDensity.current) { popup.yPos.toDp() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = screenWidth - H_PAD2.dp
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    Popup(properties = PopupProperties(focusable = true), onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
        ) {
            val maxVisible = 5
            val rowHeight = 58.dp
            val maxHeight = rowHeight * maxVisible - rowHeight / 3
            val height = if (popup.entries.size >= maxVisible) maxHeight else rowHeight * popup.entries.size
            val listState = rememberLazyListState()
            if (popup.entries.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .offset(
                            x = H_PAD.dp, y = (yDp - height - safeTopDp).coerceAtLeast(0.dp)
                        )
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large
                        )
                        .widthIn(max = maxWidth)
                        .padding(horizontal = H_PAD.dp, vertical = 12.dp)
                        .fadingEdges()
                ) {
                    items(popup.entries) { item ->
                        val key = SelectionKey.Shortcut(item.info.`package`, item.info.id)
                        val selected = selectedMap.containsKey(key)
                        SelectorRowLayout(
                            leading = { RowIcon(item.icon) },
                            label = item.label,
                            selected = selected,
                            modifier = Modifier.pointerInput(item) {
                                detectTapGestures { onToggle(item) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectorLetterBar(
    tags: List<LauncherItem.Tag>,
    allApps: Map<Char, List<LauncherItem.App>>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val letters = remember(tags, allApps) {
        buildList {
            add('#')
            addAll(allApps.keys.sorted())
        }
    }
    val scrollIndexes = remember(tags, allApps) {
        buildList {
            var index = 0
            add(index) // # header
            index += 1 + tags.size
            if (tags.isNotEmpty()) index += 1 // spacer after tags
            allApps.keys.sorted().forEach { letter ->
                add(index)
                val count = allApps[letter]?.size ?: 0
                index += 1 + count + 1 // + spacer
            }
        }
    }

    val haptic = LocalHapticFeedback.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val (height, botOffset, letterSizeDp) = remember(letters, screenHeight) {
        val slotFillFraction = 0.85f
        val botOffset = (1f / 8f) * screenHeight
        val barHeight = screenHeight - (1f / 3f) * screenHeight - botOffset
        val letterSizeInDp = (barHeight / letters.size * slotFillFraction).coerceAtMost(48.dp)
        Triple(barHeight, botOffset, letterSizeInDp)
    }
    var isTouched by remember { mutableStateOf(false) }
    var letterIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(scrollIndexes) {
        snapshotFlow { letterIndex }.distinctUntilChanged().collect { idx ->
            idx?.let {
                scrollIndexes.getOrNull(idx)?.let { target ->
                    listState.scrollToItem(target)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .padding(bottom = botOffset)
            .height(height)
            .width(if (isTouched) (2.5 * H_PAD2).dp else 1.5 * H_PAD2.dp)
            .pointerInput(letters, scrollIndexes) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    letterIndex = ((down.position.y / size.height) * letters.size).toInt()
                    drag(down.id) { change ->
                        isTouched = true
                        change.consume()
                        val idx = ((change.position.y / size.height) * letters.size).toInt()
                        if (idx in letters.indices) {
                            letterIndex = idx
                        } else {
                            letterIndex = null
                        }
                    }
                    isTouched = false
                }
            }) {
        letters.forEachIndexed { i, letter ->
            Box(
                modifier = Modifier
                    .width(letterSizeDp)
                    .height(letterSizeDp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = with(density) { letterSizeDp.toSp() }
                    ),
                    color = if (i == letterIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            }
        }
    }
}
