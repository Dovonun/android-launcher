package com.example.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ManageTagScreen(
    tagId: Long,
    tagName: String,
    appsVM: AppsVM,
    viewVM: ViewVM
) {
    val tag by appsVM.getTag(tagId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Spacer(modifier = Modifier.weight(1f)) // Push everything to bottom
            
            val displayTitle = if (tagId == TAG.FAV) "Favorites" else tagName
            Text(
                text = "Manage: $displayTitle",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            tag?.let { t ->
                val items = t.items
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false), // Take remaining space but only if needed
                    contentPadding = PaddingValues(bottom = 100.dp),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    itemsIndexed(items) { index, item ->
                        ListItem(
                            headlineContent = { Text(item.label) },
                            leadingContent = { RowIcon(item) },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        enabled = index < items.size - 1,
                                        onClick = {
                                            val newList = items.toMutableList()
                                            val moving = newList.removeAt(index)
                                            newList.add(index + 1, moving)
                                            scope.launch { appsVM.updateOrder(tagId, newList) }
                                        }
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                                    }
                                    IconButton(
                                        enabled = index > 0,
                                        onClick = {
                                            val newList = items.toMutableList()
                                            val moving = newList.removeAt(index)
                                            newList.add(index - 1, moving)
                                            scope.launch { appsVM.updateOrder(tagId, newList) }
                                        }
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
            } ?: Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        // Floating Action Button with Checkmark
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
