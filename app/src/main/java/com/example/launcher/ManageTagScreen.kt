package com.example.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
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
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val safeTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

    var localItems by remember(tagId) { mutableStateOf<List<LauncherItem>>(emptyList()) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var pressedKey by remember { mutableStateOf<String?>(null) }
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
        pressedKey = null
        draggedKey = null
        dragOffsetY = 0f
        dragMoved = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = H_PAD2.dp)
                .padding(bottom = 1f / 8f * screenHeight),
            userScrollEnabled = false,
            reverseLayout = true,
            verticalArrangement = Arrangement.Bottom
        ) {
            itemsIndexed(
                items = localItems,
                key = { _, item -> manageItemKey(item) }
            ) { _, item ->
                val rowKey = manageItemKey(item)
                val isDragged = draggedKey == rowKey
                val isSelected = isDragged || pressedKey == rowKey
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

                SwipeToDismissBox(
                    modifier = Modifier.zIndex(if (isDragged) 10f else 0f),
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
                                    .padding(horizontal = 28.dp),
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
                                if (isSelected) {
                                    Modifier.shadow(10.dp, MaterialTheme.shapes.large)
                                } else {
                                    Modifier
                                }
                            )
                            .pointerInput(rowKey) {
                                detectTapGestures(
                                    onPress = {
                                        pressedKey = rowKey
                                        tryAwaitRelease()
                                        if (draggedKey == null) pressedKey = null
                                    }
                                )
                            }
                            .pointerInput(rowKey) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        draggedKey = rowKey
                                        dragOffsetY = 0f
                                        dragMoved = false
                                    },
                                    onDragEnd = finishDrag,
                                    onDragCancel = finishDrag,
                                    onVerticalDrag = { change, dragAmount ->
                                        if (draggedKey != rowKey) return@detectVerticalDragGestures
                                        change.consume()
                                        dragOffsetY += dragAmount

                                        val draggingInfo = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == rowKey }
                                            ?: return@detectVerticalDragGestures

                                        val rowSpan = draggingInfo.size.toFloat().coerceAtLeast(1f)
                                        val halfSpan = rowSpan / 2f
                                        val startIndex = localItems.indexOfFirst { manageItemKey(it) == rowKey }
                                        if (startIndex < 0) return@detectVerticalDragGestures

                                        var workingIndex = startIndex
                                        var workingOffset = dragOffsetY
                                        val reordered = localItems.toMutableList()
                                        val toIndex = when {
                                            workingOffset <= -halfSpan && workingIndex < reordered.lastIndex -> workingIndex + 1
                                            workingOffset >= halfSpan && workingIndex > 0 -> workingIndex - 1
                                            else -> -1
                                        }
                                        if (toIndex >= 0) {
                                            reordered.add(toIndex, reordered.removeAt(workingIndex))
                                            workingOffset += if (toIndex > workingIndex) rowSpan else -rowSpan
                                            localItems = reordered
                                            dragOffsetY = workingOffset
                                            dragMoved = true
                                        }
                                    }
                                )
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val rowBgColor = when {
                                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                                swipeActive -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawWithContent {
                                        val leftInset = 0f
                                        val rightInset = 8.dp.toPx()
                                        val radius = 14.dp.toPx()
                                        if (rowBgColor.alpha > 0f) {
                                            drawRoundRect(
                                                color = rowBgColor,
                                                topLeft = Offset(leftInset, 0f),
                                                size = androidx.compose.ui.geometry.Size(
                                                    width = size.width - leftInset - rightInset,
                                                    height = size.height
                                                ),
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                                            )
                                        }
                                        drawContent()
                                    }
                                    .padding(end = H_PAD2.dp + 8.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    LauncherRowLayout(item = item)
                                }
                                DragHandleDots(
                                    modifier = Modifier
                                        .padding(start = H_PAD.dp, end = H_PAD.dp + 8.dp)
                                        .size(width = 16.dp, height = 24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = H_PAD2.dp, top = safeTop + 8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit $tagName",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
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
