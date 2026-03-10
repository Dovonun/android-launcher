package com.example.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

const val LEFT_PAD = 8

private data class ManageRow(
    val rowId: Long,
    // If a LauncherItem has an id you could remove this id.
    val item: LauncherItem
)

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
    var dragMoved by remember { mutableStateOf(false) }

    val persistOrder: (List<ManageRow>) -> Unit = { rows ->
        scope.launch(Dispatchers.IO) { appsVM.updateOrder(tag.id, rows.map { it.item }) }
    }

    val finishDrag: (Long) -> Unit = { rowId ->
        if (draggedRowId == rowId) {
            if (dragMoved) {
                persistOrder(localRows)
            }

            draggedRowId = null
            settleTargetId = rowId
            dragMoved = false
            scope.launch {
                settleAnim.snapTo(dragOffsetY)
                settleAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.85f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                if (settleTargetId == rowId) {
                    settleTargetId = null
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
                // Why do I need this?
                var thresholdArmed by remember(rowId) { mutableStateOf(false) }
                var reachedThreshold by remember(rowId) { mutableStateOf(false) }
                val backgroundStartPad = 8.dp
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
                        .then(if (isDragged) Modifier else Modifier.animateItemPlacement()),
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
        }

        FloatingActionButton(
            onClick = { viewVM.setView(View.Favorites) },
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
