package com.example.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ManageTagScreen(
    tagId: Long,
    tagName: String,
    appsVM: AppsVM,
    viewVM: ViewVM
) {
    val tag by appsVM.getTag(tagId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    var localItems by remember(tagId) { mutableStateOf<List<LauncherItem>>(emptyList()) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableStateOf(false) }
    var pendingPersistKeys by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(tag?.items, draggedKey, pendingPersistKeys) {
        val upstream = tag?.items ?: emptyList()
        val upstreamKeys = upstream.map(::manageItemKey)
        pendingPersistKeys?.let { pending ->
            if (upstreamKeys == pending) {
                pendingPersistKeys = null
            } else {
                return@LaunchedEffect
            }
        }
        if (draggedKey == null) {
            localItems = upstream
        }
    }

    val persistOrder: (List<LauncherItem>) -> Unit = { items ->
        pendingPersistKeys = items.map(::manageItemKey)
        scope.launch { appsVM.updateOrder(tagId, items) }
    }

    val finishDrag: () -> Unit = {
        if (dragMoved) {
            persistOrder(localItems)
        }
        draggedKey = null
        dragOffsetY = 0f
        dragMoved = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Spacer(modifier = Modifier.weight(1f))

            val displayTitle = if (tagId == TAG.FAV) "Favorites" else tagName
            Text(
                text = "Manage: $displayTitle",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            tag?.let {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 100.dp),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    itemsIndexed(
                        items = localItems,
                        key = { _, item -> manageItemKey(item) }
                    ) { _, item ->
                        val rowKey = manageItemKey(item)
                        val isDragged = draggedKey == rowKey
                        var rowWidthPx by remember(rowKey) { mutableFloatStateOf(1f) }
                        var thresholdArmed by remember(rowKey) { mutableStateOf(false) }
                        var dismissStateRef by remember(rowKey) { mutableStateOf<SwipeToDismissBoxState?>(null) }
                        val dismissThresholdFraction = 0.30f

                        val dismissState = rememberSwipeToDismissBoxState(
                            positionalThreshold = { totalDistance -> totalDistance * dismissThresholdFraction },
                            confirmValueChange = { value ->
                                if (draggedKey != null) return@rememberSwipeToDismissBoxState false
                                if (value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart) {
                                    val offset = dismissStateRef?.let { runCatching { it.requireOffset() }.getOrDefault(0f) } ?: 0f
                                    val reachedThreshold = abs(offset) >= rowWidthPx * dismissThresholdFraction
                                    if (!reachedThreshold) return@rememberSwipeToDismissBoxState false
                                    val updated = localItems.filterNot { manageItemKey(it) == rowKey }
                                    if (updated.size != localItems.size) {
                                        localItems = updated
                                        persistOrder(updated)
                                    }
                                    true
                                } else {
                                    true
                                }
                            }
                        )
                        LaunchedEffect(dismissState) { dismissStateRef = dismissState }
                        val dismissDirection = dismissState.dismissDirection
                        val dismissOffset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
                        val reachedThreshold = abs(dismissOffset) >= rowWidthPx * dismissThresholdFraction
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

                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = draggedKey == null,
                            enableDismissFromEndToStart = draggedKey == null,
                            backgroundContent = {
                                if (dismissDirection != SwipeToDismissBoxValue.Settled) {
                                    val align = if (dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                        Alignment.CenterStart
                                    } else {
                                        Alignment.CenterEnd
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 20.dp),
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
                                    .then(
                                        if (isDragged) {
                                            Modifier.shadow(8.dp, MaterialTheme.shapes.medium)
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    LauncherRowLayout(item = item)
                                }
                                DragHandleDots(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(width = 16.dp, height = 24.dp)
                                        .pointerInput(rowKey) {
                                            detectDragGestures(
                                                onDragStart = {
                                                draggedKey = rowKey
                                                dragOffsetY = 0f
                                                dragMoved = false
                                                },
                                                onDragEnd = finishDrag,
                                                onDragCancel = finishDrag,
                                                onDrag = { change, dragAmount ->
                                                if (draggedKey != rowKey) return@detectDragGestures
                                                change.consume()
                                                dragOffsetY += dragAmount.y

                                                val draggingInfo = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.key == rowKey }
                                                    ?: return@detectDragGestures

                                                val rowSpan = draggingInfo.size.toFloat().coerceAtLeast(1f)
                                                val halfSpan = rowSpan / 2f
                                                val startIndex = localItems.indexOfFirst { manageItemKey(it) == rowKey }
                                                if (startIndex < 0) return@detectDragGestures

                                                var workingIndex = startIndex
                                                var workingOffset = dragOffsetY
                                                val reordered = localItems.toMutableList()
                                                var swapped = false

                                                while (true) {
                                                    val toIndex = when {
                                                        workingOffset <= -halfSpan && workingIndex < reordered.lastIndex -> workingIndex + 1
                                                        workingOffset >= halfSpan && workingIndex > 0 -> workingIndex - 1
                                                        else -> break
                                                    }
                                                    reordered.add(toIndex, reordered.removeAt(workingIndex))
                                                    workingOffset += if (toIndex > workingIndex) rowSpan else -rowSpan
                                                    workingIndex = toIndex
                                                    swapped = true
                                                }

                                                if (swapped) {
                                                    localItems = reordered
                                                    dragOffsetY = workingOffset
                                                    dragMoved = true
                                                }
                                                }
                                            )
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
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

@Composable
private fun DragHandleDots(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 9f
        val left = size.width * 0.35f
        val right = size.width * 0.65f
        val ys = listOf(size.height * 0.2f, size.height * 0.5f, size.height * 0.8f)
        ys.forEach { y ->
            drawCircle(color = color, radius = radius, center = Offset(left, y))
            drawCircle(color = color, radius = radius, center = Offset(right, y))
        }
    }
}

private fun manageItemKey(item: LauncherItem): String = when (item) {
    is LauncherItem.App -> "app:${item.info.componentName.packageName}"
    is LauncherItem.Shortcut -> "shortcut:${item.info.`package`}:${item.info.id}"
    is LauncherItem.Tag -> "tag:${item.id}"
    is LauncherItem.Placeholder -> "placeholder:${item.kind}:${item.label}"
}
