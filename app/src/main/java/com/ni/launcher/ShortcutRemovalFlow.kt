package com.ni.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Shared by Manage swipe deletion and the shortcut context sheet. */
@Composable
internal fun ShortcutRemovalFlow(
    item: LauncherItem.Shortcut,
    parentId: Long,
    appsVM: AppsVM,
    onRemoved: (ShortcutRemovalAction) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var working by remember(item, parentId) { mutableStateOf(false) }
    var error by remember(item, parentId) { mutableStateOf<String?>(null) }
    val choose = requiresUnpinChoice(item.info.isPinned, item.info.isDeclaredInManifest, item.info.isDynamic)

    fun remove(action: ShortcutRemovalAction) {
        if (working) return
        working = true
        error = null
        scope.launch {
            try {
                appsVM.removeShortcutFromTag(item, parentId, action)
                onRemoved(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The shortcut could not be removed."
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(item, parentId) {
        if (!choose) remove(ShortcutRemovalAction.HIDE)
    }
    BackHandler(enabled = working) { /* Wait for the requested platform/database operation. */ }

    if (choose || working || error != null) {
        AlertDialog(
            onDismissRequest = { if (!working) onDismiss() },
            title = { Text(if (choose) "Remove ${item.label}?" else "Hide ${item.label}") },
            text = {
                Text(error ?: if (working) "Saving…" else
                    "Unpin removes this shortcut from all your tags; you may need to recreate it in its app. Hide marks it Hidden, but it may still appear until hiding is supported.")
            },
            confirmButton = {
                if (choose) {
                    TextButton(onClick = { remove(ShortcutRemovalAction.UNPIN) }, enabled = !working) { Text("Unpin") }
                } else if (!working) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            },
            dismissButton = {
                if (choose) {
                    Row {
                        TextButton(onClick = { remove(ShortcutRemovalAction.HIDE) }, enabled = !working) { Text("Hide") }
                        TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
                    }
                }
            }
        )
    }
}
