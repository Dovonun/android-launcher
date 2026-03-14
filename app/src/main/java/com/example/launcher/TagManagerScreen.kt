package com.example.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val initialTags by produceState(initialValue = emptyList<LauncherItem.Tag>()) {
        value = appsVM.allTags.first()
    }
    var tags by remember(initialTags) { mutableStateOf(initialTags) }
    var editingTagId by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<LauncherItem.Tag?>(null) }
    var popupState by remember { mutableStateOf<TagPreviewPopupState?>(null) }

    val startRename: (LauncherItem.Tag) -> Unit = { tag ->
        editingTagId = tag.id
        editText = tag.name
    }
    val cancelRename: () -> Unit = {
        editingTagId = null
        editText = ""
    }
    fun commitRename(tag: LauncherItem.Tag) {
        val trimmed = editText.trim()
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

    BackHandler { viewVM.setView(View.Favorites) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = H_PAD2.dp),
            contentPadding = PaddingValues(
                top = 1f / 3f * screenHeight,
                bottom = 2f / 3f * screenHeight
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(
                items = tags,
                key = { it.id }
            ) { tag ->
                val isSystem = tag.id == TAG.FAV || tag.id == TAG.PINNED
                val isEditing = editingTagId == tag.id
                var layoutCoordinates: LayoutCoordinates? = null
                var fired by remember { mutableStateOf(false) }

                val rowAlpha = if (isSystem) 0.55f else 1f

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(H_PAD.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(rowAlpha)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surface)
                        .onGloballyPositioned { coordinates -> layoutCoordinates = coordinates }
                        .pointerInput(tag) {
                            detectTapGestures(
                                onTap = {
                                    if (!isSystem && !isEditing) startRename(tag)
                                },
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
                        .padding(vertical = 6.dp, horizontal = H_PAD.dp)
                ) {
                    RowIcon(tag.representative.icon)
                    if (isEditing) {
                        TextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent
                            )
                        )
                        IconButton(onClick = { commitRename(tag) }) {
                            Icon(Icons.Default.Check, contentDescription = "Confirm rename")
                        }
                        IconButton(onClick = cancelRename) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel rename")
                        }
                    } else {
                        RowLabel(tag.name)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = tag.items.size.toString(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                            .padding(vertical = 8.dp)
                            .height(42.dp)
                    ) {
                        Text(
                            text = "Manage Tag",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge
                        )
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
