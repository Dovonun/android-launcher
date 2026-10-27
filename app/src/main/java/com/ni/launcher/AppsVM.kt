package com.ni.launcher

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ni.launcher.model.AppNode
import com.ni.launcher.model.Command
import com.ni.launcher.model.ItemKey
import com.ni.launcher.model.LauncherProjections
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Thin UI facade over the app-scoped graph layer: exposes [LauncherProjections] and turns user
 * intents into [Command]s. No DAO, no IPC for reads, nothing suspends on the DB.
 */
class AppsVM(application: Application) : AndroidViewModel(application) {
    private val app = application as NiLauncher
    private val store = app.graphStore
    private val projections: LauncherProjections = app.projections
    private val launcherApps: LauncherApps = application.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = android.os.Process.myUserHandle()

    // Reactive all-apps list for main screen rendering.
    val uiAllGrouped: StateFlow<Map<Char, List<LauncherItem>>> = projections.allApps

    // Reactive favorites list for home screen rendering.
    val favorites: StateFlow<List<LauncherItem>> = projections.favorites

    val allTags: StateFlow<List<LauncherItem.Tag>> = projections.allTags

    fun getTag(id: Long): Flow<LauncherItem.Tag?> =
        projections.tagView.map { it.tags[id] }.distinctUntilChanged()

    /** Current tag row (synchronous read). */
    fun tagSnapshot(id: Long): LauncherItem.Tag? = projections.tagView.value.tags[id]

    /** Current resolved children of a tag (synchronous read). */
    fun tagItems(id: Long): List<LauncherItem> = projections.tagView.value.rowsByTag[id].orEmpty()

    fun getTagsForItem(item: LauncherItem): Flow<List<String>> {
        val key = item.itemKey() ?: return projections.tagsByKey.map { emptyList() }
        return projections.tagsByKey.map { it[key].orEmpty() }.distinctUntilChanged()
    }

    private val _toast = MutableSharedFlow<String>()
    val toast: SharedFlow<String> = _toast

    fun launch(item: LauncherItem) {
        viewModelScope.launch {
            when (val target = resolveLaunchTarget(item)) {
                is LauncherItem.App -> {
                    val component = ComponentName.unflattenFromString(target.node.component)
                    if (component == null) {
                        _toast.emit("App could not be started")
                    } else {
                        try {
                            launcherApps.startMainActivity(component, user, null, null)
                        } catch (_: ActivityNotFoundException) {
                            _toast.emit("App could not be started")
                        }
                    }
                }
                is LauncherItem.Shortcut -> {
                    val node = target.node
                    if (!node.enabled) {
                        _toast.emit(node.disabledMsg ?: "Shortcut is disabled")
                        return@launch
                    }
                    try {
                        launcherApps.startShortcut(node.pkg, node.id, null, null, user)
                    } catch (_: ActivityNotFoundException) {
                        _toast.emit("Shortcut could not be started")
                    } catch (_: SecurityException) {
                        _toast.emit("Shortcut permission denied")
                    } catch (_: IllegalStateException) {
                        _toast.emit("Shortcut could not be started")
                    }
                }
                is LauncherItem.Placeholder -> _toast.emit(target.label)
                is LauncherItem.Tag -> error("Unreachable launch target: unresolved Tag")
            }
        }
    }

    /** Swipe popup content, read synchronously from the precomputed projections. */
    fun popupEntriesSnapshot(item: LauncherItem): List<LauncherItem> = when (item) {
        is LauncherItem.Tag -> tagItems(item.id).drop(1)
        is LauncherItem.Shortcut -> emptyList()
        is LauncherItem.Placeholder -> emptyList()
        is LauncherItem.App -> projections.appPopups.value[item.node.pkg].orEmpty()
    }

    fun openAppSettings(node: AppNode) {
        val component = ComponentName.unflattenFromString(node.component) ?: return
        launcherApps.startAppDetailsActivity(component, user, null, null)
    }

    fun uninstallApp(node: AppNode) {
        val context = getApplication<Application>()
        context.startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = "package:${node.pkg}".toUri()
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Package manager updates can lag while uninstall UI is in foreground.
        // Re-query a few times after the request so All Apps reflects removals reliably.
        viewModelScope.launch {
            repeat(4) {
                delay(600)
                app.systemSource.requestRefresh()
            }
        }
    }

    // Snapshot-only builder for bottom-sheet actions at open time.
    fun sheetActionsSnapshot(
        item: LauncherItem,
        parent: LauncherItem.Tag? = null,
        onNavigate: (View) -> Unit = {}
    ): List<SheetAction> = buildList {
        if (parent == null) {
            val inFavorites = isInFavorites(item)
            val favoriteLabel = if (inFavorites) "Remove from Favorites" else "Add to Favorites"
            add(SheetAction(favoriteLabel) {
                if (inFavorites) {
                    removeItemFromParent(item, TAG.FAV)
                } else {
                    val favTag = tagSnapshot(TAG.FAV) ?: return@SheetAction
                    val favItems = tagItems(TAG.FAV)
                    val key = item.itemKey()
                    val exists = key != null && favItems.any { it.itemKey() == key }
                    val nextItems = if (exists || key == null) favItems else favItems + item
                    onNavigate(View.ManageTag(favTag, nextItems))
                }
            })
        } else {
            add(SheetAction("Remove from ${parent.name}") {
                removeItemFromParent(item, parent.id)
            })
            add(SheetAction("Manage ${parent.name}") {
                onNavigate(View.ManageTag(tagSnapshot(parent.id) ?: parent, tagItems(parent.id)))
            })
        }

        if (item is LauncherItem.Tag) {
            add(SheetAction("Manage ${item.name}") {
                onNavigate(View.ManageTag(tagSnapshot(item.id) ?: item, tagItems(item.id)))
            })
        } else {
            add(SheetAction("Create Tag") {
                createTagFromItem(item, onNavigate)
            })
        }

        val terminal = resolveTerminalItem(item)
        if (terminal is LauncherItem.App) {
            add(SheetAction("App Settings") { openAppSettings(terminal.node) })
            add(SheetAction("Uninstall") { uninstallApp(terminal.node) })
        }
    }

    private fun removeItemFromParent(item: LauncherItem, parentId: Long) {
        val key = item.itemKey() ?: return
        store.dispatch(Command.Remove(parentId, key))
    }

    private fun isInFavorites(item: LauncherItem): Boolean {
        val key = item.itemKey() ?: return false
        return store.graph.value.user.tags[TAG.FAV]?.contains(key) == true
    }

    private fun createTagFromItem(item: LauncherItem, onNavigate: (View) -> Unit) {
        val tagItems = when (item) {
            is LauncherItem.App -> listOf(item) + popupEntriesSnapshot(item)
            is LauncherItem.Shortcut -> listOf(item)
            is LauncherItem.Tag -> return
            is LauncherItem.Placeholder -> return
        }
        val newTagId = try {
            store.newTagId()
        } catch (_: IllegalStateException) {
            viewModelScope.launch { _toast.emit("Launcher data is still loading") }
            return
        }
        val newTagName = "${item.label} Tag"
        store.dispatch(
            Command.Create(
                id = newTagId,
                name = newTagName,
                seed = tagItems.mapNotNull { it.toEdge() },
                addToFav = true,
            )
        )
        viewModelScope.launch { _toast.emit("Tag created and added to Favorites") }

        val representative = tagItems.firstOrNull()
            ?: LauncherItem.Placeholder(PlaceholderKind.EMPTY_TAG, "Empty tag")
        val uiTag = LauncherItem.Tag(
            id = newTagId,
            name = newTagName,
            representative = representative,
            count = tagItems.size,
        )
        onNavigate(View.ManageTag(uiTag, tagItems))
    }

    private fun resolveTerminalItem(item: LauncherItem): LauncherItem {
        var current = item
        val visited = mutableSetOf<Long>()
        while (current is LauncherItem.Tag) {
            if (!visited.add(current.id)) {
                return LauncherItem.Placeholder(PlaceholderKind.RECURSION, "Recursion")
            }
            current = current.representative
        }
        return current
    }

    private fun keysOf(items: List<LauncherItem>): List<ItemKey> = items.mapNotNull { it.itemKey() }

    /** New order for [tagId]; keys not in [items] are dropped (as before). */
    fun updateOrder(tagId: Long, items: List<LauncherItem>) {
        store.dispatch(Command.Reorder(tagId, keysOf(items)))
    }

    /** Appends items missing from [tagId]. */
    fun ensureItemsInTag(tagId: Long, items: List<LauncherItem>) {
        store.dispatch(Command.Add(tagId, items.mapNotNull { it.toEdge() }))
    }

    /** Makes [tagId]'s children exactly [items], in order (ensure + reorder). */
    fun syncTagItemsToList(tagId: Long, items: List<LauncherItem>) {
        store.dispatch(Command.SetItems(tagId, items.mapNotNull { it.toEdge() }))
    }

    /** Same as [syncTagItemsToList]; kept for the ManageTag entry call site. */
    fun syncTagItemsInMemory(tagId: Long, items: List<LauncherItem>) = syncTagItemsToList(tagId, items)

    fun renameTag(tagId: Long, name: String) {
        store.dispatch(Command.Rename(tagId, name))
    }

    fun deleteTag(tagId: Long) {
        store.dispatch(Command.Delete(tagId))
    }
}
