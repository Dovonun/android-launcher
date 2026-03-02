package com.example.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
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
    // Q: Why do we need a list state/LazyColumn? Wouldn't normal column be enough?
    // Could it be for animations or for drag?
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

    // TODO: tag.items is not reactive. change this to a graph lookup or pass the elements
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
        // Again why lazy?
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
                            // This if is always true
                            if (updated.size != localItems.size) {
                                localItems = updated
                                persistOrder(updated)
                            }
                            true
                        } else {
                            // Q: Can this ever happen?
                            // There is a `SwipeToDismissBoxValue.Settled` but I have no clue if this is possible in the if above.
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
                // Q: How often is this triggered? Why is settled relevant?
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
                    // Can't these always be enabled? Or would that allow to delete multiple with multi touch? Would not be that bad.
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
                                    // 28.dp is too high I think
                                    // 16.dp also too high
                                    // This looks acceptable on the left
                                    .padding(horizontal = 8.dp),
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
                            // Eh, There is no animation param passed...
                            .animateItem()
                            // Q: Why would the size change?
                            .onSizeChanged { rowWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                            .graphicsLayer {
                                // Q: This could be done without the if. Would that be worse for performance?
                                if (isDragged) {
                                    translationY = dragOffsetY
                                }
                            }
                            .then(
                                if (isSelected) {
                                    // Q: The currently dragged row should have a background!
                                    // I thought that I saw some ugly shadow. Can this be removed?
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
                                        // Q: Is this some sort of conflict resolution for the horizontal delete swipe?
                                        // Or is this only for the color change?
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
                                        // Q: right now multi touch is a bit odd.
                                        // Wouldn't a prevent new drag if one is already in progress be better?
                                        // Right now the first one is canceled by the second one and releasing the first one ends the second one.
                                        if (draggedKey != rowKey) return@detectVerticalDragGestures
                                        change.consume()
                                        // Is this the correct way to do this? The current y pos would seem more correct.
                                        // Or is this to handle small positive and negative values?
                                        dragOffsetY += dragAmount

                                        val draggingInfo = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == rowKey }
                                            ?: return@detectVerticalDragGestures

                                        // Not sure what this calculates. But I guess it is related to moving the other items when one is dragged to its positon.
                                        val rowSpan = draggingInfo.size.toFloat().coerceAtLeast(1f)
                                        val halfSpan = rowSpan / 2f
                                        val startIndex = localItems.indexOfFirst { manageItemKey(it) == rowKey }
                                        if (startIndex < 0) return@detectVerticalDragGestures

                                        // What is going on here?
                                        // Here var is used but it is not changed.
                                        // This part seems to work correctly. The items move when I move over them and they pop to the correct place.
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
                                            dragOffsetY = workingOffset // Why is this needed
                                            dragMoved = true
                                        }
                                    }
                                )
                            }
                    ) {
                        // This box seems a bit too nested. Why are these nestings needed? Box for the LauncherRowLayout and a box around the row? Wouldn't one row be enough?
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val rowBgColor = when {
                                // Why are both these states needed?
                                // The background should be active if the user is touching the object should be easier than this.
                                // In CSS it would be one hover statement
                                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                                swipeActive -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // This seems over complicated. I want a visual background for my row.
                                    // The icon in the row does not have left padding so we have to somehow add a bit of space to the left, but other than that it should just be the background of my row.
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
                                Box(modifier = Modifier.weight(1f).border(2.dp, Color.Blue)) {
                                    LauncherRowLayout(item = item)
                                }
                                DragHandleDots(
                                    modifier = Modifier
                                        .padding(start = H_PAD.dp, end = H_PAD.dp + 8.dp)
                                        .size(width = 16.dp, height = 24.dp)
                                        .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                )
                            }
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
