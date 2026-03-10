package com.example.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class ManageRow(
    val rowId: Long,
    // If a LauncherItem has an id you could remove this id.
    val item: LauncherItem
)

// Depending on if this is per row or global for one row the necessary fields change.
private enum class InteractionPhase {
    // Why is idle needed?
    Idle,
    Pressed,
    DraggingVertical,
    SwipingHorizontal,
    // One moving state should be enough
    SettlingBack,
    SettlingToReorderedSlot
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
    val listState = rememberLazyListState() // seems like lazy is the correct way to implement drag and drop. But check this again please.
    val haptic = LocalHapticFeedback.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    var localRows by remember(items) { mutableStateOf<List<ManageRow>>(items.mapIndexed { index, item -> ManageRow(index.toLong(), item) }) }
    var draggedRowId by remember { mutableStateOf<Long?>(null) } // why
    var activeRowId by remember { mutableStateOf<Long?>(null) } // why
    var activePhase by remember { mutableStateOf(InteractionPhase.Idle) } // why
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableStateOf(false) }
    var settleGeneration by remember { mutableIntStateOf(0) }

    val persistOrder: (List<ManageRow>) -> Unit = { rows ->
        val items = rows.map { it.item }
        scope.launch(Dispatchers.IO) { appsVM.updateOrder(tag.id, items) } // if we can persist on leave we could do it via one call back and remove the appsVM from this screen
    }

    val finishDrag: (Long) -> Unit = { rowId ->
        if (draggedRowId == rowId) {
            if (dragMoved) {
                persistOrder(localRows)
            }

            draggedRowId = null
            dragOffsetY = 0f
            dragMoved = false

            settleGeneration += 1
            val generation = settleGeneration
            activeRowId = rowId
            activePhase = InteractionPhase.SettlingToReorderedSlot

            scope.launch {
                // This should not be needed. I want the highlight gone after it is settled!
                // The trash icon should be removed first of course.
                delay(10) // <-- this is too long it feels bad
                if (
                    settleGeneration == generation &&
                    activeRowId == rowId &&
                    activePhase == InteractionPhase.SettlingToReorderedSlot
                ) {
                    activeRowId = null
                    activePhase = InteractionPhase.Idle
                }
            }
        }
    }

    // Why do we need a box? Seems to be because of the button...
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = H_PAD2.dp)
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
                val ownsActiveState = activeRowId == rowId && activePhase != InteractionPhase.Idle
                // seems over engineered
                val canOwnInteraction = activeRowId == null || activeRowId == rowId
                var rowWidthPx by remember(rowId) { mutableFloatStateOf(1f) }
                // Why do I need this?
                var thresholdArmed by remember(rowId) { mutableStateOf(false) }
                var dismissStateRef by remember(rowId) { mutableStateOf<SwipeToDismissBoxState?>(null) }
                val dismissThresholdFraction = 0.30f

                // Seems overkill
                val dismissState = rememberSwipeToDismissBoxState(
                    positionalThreshold = { totalDistance -> totalDistance * dismissThresholdFraction },
                    confirmValueChange = { value ->
                        if (draggedRowId != null) return@rememberSwipeToDismissBoxState false
                        if (activeRowId != null && activeRowId != rowId) return@rememberSwipeToDismissBoxState false

                        when (value) {
                            SwipeToDismissBoxValue.StartToEnd,
                            SwipeToDismissBoxValue.EndToStart -> {
                                val offset = dismissStateRef
                                    ?.let { runCatching { it.requireOffset() }.getOrDefault(0f) }
                                    ?: 0f
                                val reachedThreshold = abs(offset) >= rowWidthPx * dismissThresholdFraction
                                if (!reachedThreshold) return@rememberSwipeToDismissBoxState false

                                val updated = localRows.filterNot { it.rowId == rowId }
                                localRows = updated
                                if (activeRowId == rowId) {
                                    activeRowId = null
                                    activePhase = InteractionPhase.Idle
                                }
                                persistOrder(updated)
                                true
                            }
                            SwipeToDismissBoxValue.Settled -> true
                        }
                    }
                )
                /// Mirroring?
                LaunchedEffect(dismissState) { dismissStateRef = dismissState }

                val dismissDirection = dismissState.dismissDirection
                val dismissOffset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
                val reachedThreshold = abs(dismissOffset) >= rowWidthPx * dismissThresholdFraction
                val swipeActive =
                    abs(dismissOffset) > 1f ||
                        dismissState.currentValue != SwipeToDismissBoxValue.Settled ||
                        dismissState.targetValue != SwipeToDismissBoxValue.Settled

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

                // seems bad
                LaunchedEffect(
                    rowId,
                    swipeActive,
                    dismissDirection,
                    draggedRowId,
                    activeRowId,
                    activePhase
                ) {
                    if (draggedRowId != null) return@LaunchedEffect

                    if (swipeActive && canOwnInteraction) {
                        activeRowId = rowId
                        activePhase = if (dismissDirection == SwipeToDismissBoxValue.Settled) {
                            InteractionPhase.SettlingBack
                        } else {
                            InteractionPhase.SwipingHorizontal
                        }
                    } else if (
                        !swipeActive &&
                        activeRowId == rowId &&
                        (activePhase == InteractionPhase.SwipingHorizontal ||
                            activePhase == InteractionPhase.SettlingBack)
                    ) {
                        activeRowId = null
                        activePhase = InteractionPhase.Idle
                    }
                }

                val interactionActive = ownsActiveState || swipeActive

                SwipeToDismissBox(
                    modifier = Modifier.zIndex(if (isDragged) 10f else 0f),
                    state = dismissState,
                    enableDismissFromStartToEnd = draggedRowId == null && canOwnInteraction,
                    enableDismissFromEndToStart = draggedRowId == null && canOwnInteraction,
                    backgroundContent = {
                        if (dismissDirection != SwipeToDismissBoxValue.Settled) {
                            val align = if (dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                Alignment.CenterStart
                            } else {
                                Alignment.CenterEnd
                            }
                            // Why box?
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp),
                                contentAlignment = align
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    // is armed should do the same no?
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .onSizeChanged { rowWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                            .graphicsLayer {
                                if (isDragged) {
                                    translationY = dragOffsetY
                                }
                            }
                            .pointerInput(rowId) {
                                detectTapGestures(
                                    onPress = {
                                        if (!canOwnInteraction) return@detectTapGestures
                                        settleGeneration += 1
                                        activeRowId = rowId
                                        activePhase = InteractionPhase.Pressed

                                        tryAwaitRelease()
                                        if (
                                            activeRowId == rowId &&
                                            activePhase == InteractionPhase.Pressed
                                        ) {
                                            activeRowId = null
                                            activePhase = InteractionPhase.Idle
                                        }
                                    }
                                )
                            }
                            .pointerInput(rowId) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        if (canOwnInteraction) {
                                            settleGeneration += 1
                                            activeRowId = rowId
                                            activePhase = InteractionPhase.DraggingVertical
                                            draggedRowId = rowId
                                            dragOffsetY = 0f
                                            dragMoved = false
                                        }
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
                            .clip(MaterialTheme.shapes.large) // what is clip
                            .background(
                                if (interactionActive) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                }
                            )
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

        FloatingActionButton(
            onClick = { viewVM.setView(View.Favorites) }, // if this is a callback we can remove the viewVM.
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

private fun persistItemKey(item: LauncherItem): String = when (item) {
    is LauncherItem.App -> "app:${item.info.componentName.packageName}"
    is LauncherItem.Shortcut -> "shortcut:${item.info.`package`}:${item.info.id}"
    is LauncherItem.Tag -> "tag:${item.id}"
    is LauncherItem.Placeholder -> "placeholder:${item.kind}:${item.label}"
    // What do I do with place holders? I could not show place holders. The hard case is the empty place holder. Does it vanish when I add an element? How does it work? Tags should be shown. Empty is not a problem in that case because favorites can't be empty. But this means we don't need the graph we need the list before the graph. One without reps.
}
