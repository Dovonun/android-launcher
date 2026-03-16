package com.example.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class TagPreviewPopupState(
    val tag: LauncherItem.Tag,
    val entries: List<LauncherItem>,
    val yPos: Float
)

@Composable
fun TagManagerScreen(
    appsVM: AppsVM,
    viewVM: ViewVM
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current

    val initialTags by produceState(initialValue = emptyList<LauncherItem.Tag>()) {
        value = appsVM.allTags.first()
    }
    var tags by remember(initialTags) { mutableStateOf(initialTags) }
    var editingTagId by remember { mutableStateOf<Long?>(null) }
    var editHasFocused by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(TextFieldValue("")) }
    var deleteCandidate by remember { mutableStateOf<LauncherItem.Tag?>(null) }
    var popupState by remember { mutableStateOf<TagPreviewPopupState?>(null) }

    val startRename: (LauncherItem.Tag) -> Unit = { tag ->
        editingTagId = tag.id
        editHasFocused = false
        editValue = TextFieldValue(tag.name, selection = TextRange(0, tag.name.length))
    }
    val cancelRename: () -> Unit = {
        editingTagId = null
        editHasFocused = false
        editValue = TextFieldValue("")
    }
    fun commitRename(tag: LauncherItem.Tag) {
        val trimmed = editValue.text.trim()
        cancelRename()
        if (trimmed.isEmpty() || trimmed == tag.name) return
        tags = tags.map { if (it.id == tag.id) it.copy(name = trimmed) else it }
        scope.launch(Dispatchers.IO) { appsVM.renameTag(tag.id, trimmed) }
    }

    val onDelete: (LauncherItem.Tag) -> Unit = { tag ->
        deleteCandidate = tag
    }
    val confirmDelete: (LauncherItem.Tag) -> Unit = { tag ->
        deleteCandidate = null
        editingTagId = if (editingTagId == tag.id) null else editingTagId
        tags = tags.filterNot { it.id == tag.id }
        scope.launch(Dispatchers.IO) { appsVM.deleteTag(tag.id) }
    }

    BackHandler {
        if (editingTagId != null) cancelRename() else viewVM.setView(View.Favorites)
    }

    val listContentPadding by remember(listState, tags, density, screenHeight) {
        derivedStateOf {
            val minPadding = 1f / 8f * screenHeight
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val totalCount = layoutInfo.totalItemsCount
            if (totalCount == 0) {
                return@derivedStateOf androidx.compose.foundation.layout.PaddingValues(
                    top = minPadding,
                    bottom = minPadding
                )
            }
            if (visibleItems.size == totalCount && layoutInfo.viewportSize.height > 0) {
                val spacingPx = with(density) { 6.dp.toPx() }
                val contentHeightPx = visibleItems.sumOf { it.size } +
                    spacingPx * (visibleItems.size - 1).coerceAtLeast(0)
                val centerPaddingPx =
                    ((layoutInfo.viewportSize.height - contentHeightPx) / 2f).coerceAtLeast(0f)
                val centerPadding = with(density) { centerPaddingPx.toDp() }
                val finalPadding = if (centerPadding > minPadding) centerPadding else minPadding
                androidx.compose.foundation.layout.PaddingValues(
                    top = finalPadding,
                    bottom = finalPadding
                )
            } else {
                androidx.compose.foundation.layout.PaddingValues(
                    top = minPadding,
                    bottom = minPadding
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = H_PAD2.dp),
            contentPadding = listContentPadding,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                items = tags.asReversed(),
                key = { it.id }
            ) { tag ->
                val isSystem = tag.id == TAG.FAV || tag.id == TAG.PINNED
                val isEditing = editingTagId == tag.id
                var layoutCoordinates: LayoutCoordinates? = null
                var fired by remember { mutableStateOf(false) }
                val focusRequester = remember(tag.id) { FocusRequester() }

                val rowAlpha = if (isSystem) 0.55f else 1f

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(H_PAD.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(rowAlpha)
                        .onGloballyPositioned { coordinates -> layoutCoordinates = coordinates }
                        .pointerInput(tag) {
                            detectTapGestures(
                                onLongPress = {
                                    if (!isSystem && !isEditing) onDelete(tag)
                                }
                            )
                        }
                        .pointerInput(tag) {
                            detectHorizontalDragGestures(onDragStart = { fired = false }) { change, drag ->
                                if (isEditing) return@detectHorizontalDragGestures
                                if (fired) return@detectHorizontalDragGestures
                                if (drag > 50f) {
                                    layoutCoordinates?.boundsInWindow()?.bottom?.let { y ->
                                        fired = true
                                        change.consume()
                                        val entries = tag.items
                                        if (entries.isEmpty()) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        popupState = TagPreviewPopupState(tag, entries, y)
                                    }
                                }
                            }
                        }
                        .padding(vertical = 6.dp)
                ) {
                    RowIcon(tag.representative.icon)
                    if (isEditing) {
                        TextField(
                            value = editValue,
                            onValueChange = { editValue = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                commitRename(tag)
                                keyboardController?.hide()
                            }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        editHasFocused = true
                                    } else if (editHasFocused && editingTagId == tag.id) {
                                        cancelRename()
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                        commitRename(tag)
                                        keyboardController?.hide()
                                        true
                                    } else {
                                        false
                                    }
                                }
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RowLabel(tag.name)
                                    if (isSystem) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "System tag",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(
                                                id = R.drawable.edit_24dp_e3e3e3_fill0_wght400_grad0_opsz24
                                            ),
                                            contentDescription = "Rename tag",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clickable { startRename(tag) }
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = tag.items.size.toString(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = H_PAD2.dp)
                        )
                    }
                }

                if (isEditing) {
                    androidx.compose.runtime.LaunchedEffect(tag.id, editingTagId) {
                        if (editingTagId == tag.id) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    }
                }
            }
        }
    }

    deleteCandidate?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(text = "Delete tag") },
            text = { Text(text = "Delete \"${tag.name}\"? This will remove it everywhere.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete(tag) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    popupState?.let { popup ->
        TagPreviewPopup(
            popup = popup,
            onManage = {
                scope.launch {
                    val latest = appsVM.getTag(popup.tag.id).first() ?: return@launch
                    popupState = null
                    viewVM.setView(View.ManageTag(latest, latest.items))
                }
            },
            onDismiss = { popupState = null }
        )
    }
}

@Composable
private fun TagPreviewPopup(
    popup: TagPreviewPopupState,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    val safeTopDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val yDp = with(LocalDensity.current) { popup.yPos.toDp() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = screenWidth - H_PAD2.dp
    val maxVisible = 6
    val rowHeight = 58.dp
    val items = popup.entries
    val previewItems = items.take(maxVisible)
    val hasMore = items.size > maxVisible
    val count = 1 + previewItems.size + if (hasMore) 1 else 0
    val maxHeight = rowHeight * maxVisible - rowHeight / 3
    val height = if (count >= maxVisible) maxHeight else rowHeight * count
    val listState = rememberLazyListState()

    Popup(properties = PopupProperties(focusable = true), onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .offset(x = H_PAD.dp, y = (yDp - height - safeTopDp).coerceAtLeast(0.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
                    .widthIn(max = maxWidth)
                    .padding(horizontal = H_PAD.dp, vertical = 12.dp)
            ) {
                item(key = "manage") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onManage()
                            }
                            .clip(MaterialTheme.shapes.large)
                            .padding(vertical = 6.dp)
                            .height(44.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = LEFT_PAD.dp, end = H_PAD.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "Go to manage tag",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Manage Tag",
                                modifier = Modifier.padding(start = H_PAD.dp).weight(1f),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                if (previewItems.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "Empty tag",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(previewItems) { item ->
                        LauncherRowLayout(item = item)
                    }
                }

                if (hasMore) {
                    item(key = "more") {
                        Text(
                            text = "+${items.size - maxVisible} more",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
