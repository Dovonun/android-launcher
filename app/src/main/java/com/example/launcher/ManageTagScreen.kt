package com.example.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ManageTagScreen(
    tagId: Long,
    tagName: String,
    appsVM: AppsVM,
    viewVM: ViewVM
) {
    val items by appsVM.uiList(tagId).collectAsState(emptyList())
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = tagName,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 80.dp),
                reverseLayout = true
            ) {
                itemsIndexed(items) { index, item ->
                    ListItem(
                        headlineContent = { Text(item.label) },
                        leadingContent = { RowIcon(item.icon) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    if (index < items.size - 1) {
                                        val newList = items.toMutableList()
                                        val moving = newList.removeAt(index)
                                        newList.add(index + 1, moving)
                                        scope.launch { appsVM.updateOrder(tagId, newList) }
                                    }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                                }
                                IconButton(onClick = {
                                    if (index > 0) {
                                        val newList = items.toMutableList()
                                        val moving = newList.removeAt(index)
                                        newList.add(index - 1, moving)
                                        scope.launch { appsVM.updateOrder(tagId, newList) }
                                    }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                                }
                            }
                        }
                    )
                }
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
