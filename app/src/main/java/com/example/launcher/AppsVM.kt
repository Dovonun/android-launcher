package com.example.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.launcher.data.TagDao
import com.example.launcher.data.TagEntity
import com.example.launcher.data.TagItemDao
import com.example.launcher.data.TagItemEntity
import com.example.launcher.data.TagItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LauncherItem {
    val label: String
    val icon: ImageBitmap?

    data class App(
        val info: LauncherActivityInfo,
        override val label: String,
        override val icon: ImageBitmap?
    ) : LauncherItem

    data class Shortcut(
        val info: ShortcutInfo,
        override val label: String,
        override val icon: ImageBitmap?
    ) : LauncherItem

    data class Tag(
        val id: Long,
        val name: String,
        private val getItems: () -> List<LauncherItem>,
        val representative: LauncherItem
    ) : LauncherItem {
        val items: List<LauncherItem> by lazy { getItems() }
        // Tag rows intentionally mirror the representative for list UX.
        override val label: String get() = representative.label
        override val icon: ImageBitmap? get() = representative.icon
    }

    data class Placeholder(
        val kind: PlaceholderKind,
        override val label: String,
        override val icon: ImageBitmap? = null
    ) : LauncherItem
}

enum class PlaceholderKind { EMPTY_TAG, RECURSION, MISSING_REFERENCE }

data class SheetAction(val label: String, val onTap: () -> Unit)

object TAG {
    const val FAV: Long = 1
    const val PINNED: Long = 2
}

suspend fun ensureSystemTags(tagDao: TagDao) {
    val existing = tagDao.getAllFlow().first()
    listOf(
        TagEntity(TAG.FAV, "Favorites"), TagEntity(TAG.PINNED, "Pinned")
    ).filterNot { tag -> existing.any { it.id == tag.id && it.name == tag.name } }
        .forEach { tagDao.insert(it) }
}

internal fun resolveLaunchTarget(item: LauncherItem): LauncherItem {
    var current = item
    val visitedTagIds = mutableSetOf<Long>()
    while (current is LauncherItem.Tag) {
        if (!visitedTagIds.add(current.id)) {
            error("Tag representative cycle detected while launching tagId=${current.id}")
        }
        current = current.representative
    }
    return current
}

fun createCallback(cb: () -> Unit, onShortcutsChanged: (String) -> Unit) = object : LauncherApps.Callback() {
    override fun onPackageAdded(packageName: String, user: UserHandle) = cb()
    override fun onPackageRemoved(packageName: String?, user: UserHandle?) = cb()
    override fun onPackageChanged(packageName: String?, user: UserHandle?) = cb()
    override fun onPackagesAvailable(
        packageNames: Array<String>, user: UserHandle, replacing: Boolean
    ) = cb()

    override fun onPackagesUnavailable(
        packageNames: Array<String>, user: UserHandle, replacing: Boolean
    ) = cb()

    override fun onShortcutsChanged(
        packageName: String, shortcuts: List<ShortcutInfo?>, user: UserHandle
    ) = onShortcutsChanged(packageName)
}

class AppsVM(application: Application) : AndroidViewModel(application) {
    val db = (application as NiLauncher).database
    private val tagDao = db.tagDao()
    private val tagItemDao = db.tagItemDao()
    private val launcherApps: LauncherApps = application.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = android.os.Process.myUserHandle()
    private val apps = MutableStateFlow<List<LauncherActivityInfo>>(emptyList())
    private val shortcutsRefreshTick = MutableStateFlow(0)
    private val popupShortcutMemo = mutableMapOf<String, List<ShortcutInfo>>()

    // Reactive cache for packages referenced by launcher data.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val cachedShortcuts = combine(
        tagItemDao.getDistinctPackages(), shortcutsRefreshTick
    ) { pkgs, _ -> pkgs }
        .mapLatest { pkgs ->
            coroutineScope {
                pkgs.associateWith { pkg ->
                    async { loadShortcutsForPackage(pkg) }
                }.mapValues { it.value.await() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun loadShortcutsForPackage(pkg: String): List<ShortcutInfo> =
        launcherApps.getShortcuts(
            ShortcutQuery().apply {
                setPackage(pkg)
                setQueryFlags(
                    ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST
                )
            },
            user
        ).orEmpty()

    private fun resolveShortcutsForPackage(pkg: String): List<ShortcutInfo> {
        val fromReactiveCache = cachedShortcuts.value[pkg]
        if (!fromReactiveCache.isNullOrEmpty()) return fromReactiveCache

        val fromPopupMemo = synchronized(popupShortcutMemo) { popupShortcutMemo[pkg] }
        if (!fromPopupMemo.isNullOrEmpty()) return fromPopupMemo

        val fetched = loadShortcutsForPackage(pkg)
        synchronized(popupShortcutMemo) { popupShortcutMemo[pkg] = fetched }
        return fetched
    }

    private fun refreshApps() {
        val latest = launcherApps.getActivityList(null, user)
        apps.update { latest }
        synchronized(popupShortcutMemo) {
            val knownPackages = latest.mapTo(mutableSetOf()) { it.componentName.packageName }
            popupShortcutMemo.keys.retainAll(knownPackages)
        }
        // Keep shortcut cache in sync on package add/remove/change callbacks.
        shortcutsRefreshTick.update { it + 1 }
    }
    private fun cleanup(pkg: String) {
        synchronized(popupShortcutMemo) { popupShortcutMemo.remove(pkg) }
        // Shortcut changes do not alter distinct package set, so trigger cache rebuild.
        shortcutsRefreshTick.update { it + 1 }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { ensureSystemTags(tagDao) }
        launcherApps.registerCallback(createCallback(::refreshApps, ::cleanup))
        refreshApps()
    }

    // Reactive graph that drives app/tag UI and badges.
    private val graph = combine(
        apps, cachedShortcuts, tagDao.getAllFlow(), tagItemDao.getAllItemsFlow()
    ) { apps, shortcuts, tagEntities, itemEntities ->
        val itemsByTag = itemEntities.groupBy { it.tagId }

        // --- Layer 1: Universal Resolver ---
        // One place to turn a DB entity into a UI object
        fun resolveEntity(entity: TagItemEntity): LauncherItem? = when (entity.type) {
            TagItemType.APP -> apps.find { it.componentName.packageName == entity.packageName }?.let {
                LauncherItem.App(it, entity.labelOverride ?: it.label.toString(), it.getIcon(0).toBitmap().asImageBitmap())
            }
            TagItemType.SHORTCUT -> shortcuts[entity.packageName]?.find { it.id == entity.shortcutId }?.let {
                LauncherItem.Shortcut(it, entity.labelOverride ?: it.shortLabel.toString(), launcherApps.getShortcutIconDrawable(it, 0)?.toBitmap()?.asImageBitmap())
            }
            TagItemType.TAG -> null // Tags are handled separately to avoid infinite recursion here
        }

        // --- Layer 2: Memoized Structural Resolution ---
        val repCache = mutableMapOf<Long, LauncherItem>()

        fun getRepresentative(tagId: Long, visited: Set<Long>): LauncherItem {
            if (tagId in visited) {
                return LauncherItem.Placeholder(PlaceholderKind.RECURSION, "Recursion")
            }

            repCache[tagId]?.let { return it }

            val firstItem = itemsByTag[tagId]?.firstOrNull()
                ?: return LauncherItem.Placeholder(PlaceholderKind.EMPTY_TAG, "Empty tag")

            val result = when (firstItem.type) {
                TagItemType.APP, TagItemType.SHORTCUT -> {
                    resolveEntity(firstItem)
                        ?: LauncherItem.Placeholder(PlaceholderKind.MISSING_REFERENCE, "Missing item")
                }
                TagItemType.TAG -> {
                    val targetId = firstItem.targetTagId
                    if (targetId == null) {
                        LauncherItem.Placeholder(PlaceholderKind.MISSING_REFERENCE, "Missing item")
                    } else {
                        getRepresentative(targetId, visited + tagId)
                    }
                }
            }

            if (result !is LauncherItem.Placeholder || result.kind != PlaceholderKind.RECURSION) {
                repCache[tagId] = result
            }
            return result
        }

        // --- Layer 3: UI Model Assembly ---
        val finalGraph = mutableMapOf<Long, LauncherItem.Tag>()
        
        for (tagEntity in tagEntities) {
            val rep = getRepresentative(tagEntity.id, emptySet())
            finalGraph[tagEntity.id] = LauncherItem.Tag(
                id = tagEntity.id,
                name = tagEntity.name,
                representative = rep,
                getItems = {
                    itemsByTag[tagEntity.id]?.mapNotNull { entity ->
                        if (entity.type == TagItemType.TAG) {
                            entity.targetTagId?.let { finalGraph[it] }
                        } else {
                            resolveEntity(entity)
                        }
                    } ?: emptyList()
                }
            )
        }
        
        finalGraph
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Reactive all-apps list for main screen rendering.
    val uiAllGrouped = combine(
        apps, cachedShortcuts, tagItemDao.getItemsForTag(TAG.PINNED)
    ) { apps, shortcuts, pinned ->
        val uiApps = appsToRows(apps)
        val uiPinned = pinned.mapNotNull { pin ->
            shortcuts[pin.packageName]?.find { it.id == pin.shortcutId }?.let {
                LauncherItem.Shortcut(it, pin.labelOverride ?: it.shortLabel.toString(), launcherApps.getShortcutIconDrawable(it, 0)?.toBitmap()?.asImageBitmap())
            }
        }
        (uiApps + uiPinned).sortedBy { it.label.lowercase() }
            .groupBy { it.label.first().uppercaseChar() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyMap())

    // Reactive favorites list for home screen rendering.
    val favorites = graph.map { it[TAG.FAV]?.items ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allTags = graph.map { graphMap ->
        graphMap.values.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getTag(id: Long): Flow<LauncherItem.Tag?> = graph.map { it[id] }

    fun getTagsForItem(item: LauncherItem): Flow<List<String>> = combine(
        tagDao.getAllFlow(), tagItemDao.getAllItemsFlow()
    ) { tags, items ->
        val itemTagIds = items.filter { entity ->
            when (item) {
                is LauncherItem.App -> entity.type == TagItemType.APP && entity.packageName == item.info.componentName.packageName
                is LauncherItem.Shortcut -> entity.type == TagItemType.SHORTCUT && entity.packageName == item.info.`package` && entity.shortcutId == item.info.id
                is LauncherItem.Tag -> entity.type == TagItemType.TAG && entity.targetTagId == item.id
                is LauncherItem.Placeholder -> false
            }
        }.map { it.tagId }.toSet()
        tags.filter { it.id in itemTagIds }.map { it.name }
    }

    private val _toast = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val toast: kotlinx.coroutines.flow.SharedFlow<String> = _toast

    fun launch(item: LauncherItem) {
        viewModelScope.launch {
            when (val target = resolveLaunchTarget(item)) {
                is LauncherItem.App -> launcherApps.startMainActivity(target.info.componentName, user, null, null)
                is LauncherItem.Shortcut -> launcherApps.startShortcut(target.info.`package`, target.info.id, null, null, user)
                is LauncherItem.Placeholder -> _toast.emit(target.label)
                is LauncherItem.Tag -> error("Unreachable launch target: unresolved Tag")
            }
        }
    }

    // Snapshot-only builder for swipe popup content at open time.
    suspend fun popupEntriesSnapshot(item: LauncherItem): List<LauncherItem> = when (item) {
        is LauncherItem.Tag -> item.items.drop(1)
        is LauncherItem.Shortcut -> emptyList()
        is LauncherItem.Placeholder -> emptyList()
        is LauncherItem.App -> {
            val pkg = item.info.componentName.packageName
            val shortcuts = resolveShortcutsForPackage(pkg)
            shortcuts.map {
                LauncherItem.Shortcut(
                    it,
                    it.shortLabel.toString(),
                    launcherApps.getShortcutIconDrawable(it, 0)?.toBitmap()?.asImageBitmap()
                )
            }
        }
    }

    fun openAppSettings(item: LauncherActivityInfo) {
        launcherApps.startAppDetailsActivity(item.componentName, user, null, null)
    }

    fun uninstallApp(item: LauncherActivityInfo) {
        val context = getApplication<Application>()
        context.startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = "package:${item.componentName.packageName}".toUri()
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Package manager updates can lag while uninstall UI is in foreground.
        // Poll a few times after request so All Apps reflects removals reliably.
        viewModelScope.launch {
            repeat(4) {
                delay(600)
                refreshApps()
            }
        }
    }

    // Snapshot-only builder for bottom-sheet actions at open time.
    suspend fun sheetActionsSnapshot(
        item: LauncherItem,
        parent: LauncherItem.Tag? = null,
        onNavigate: (View) -> Unit = {}
    ): List<SheetAction> = buildList {
        if (parent == null) {
            val inFavorites = isInFavorites(item)
            val favoriteLabel = if (inFavorites) "Remove from Favorites" else "Add to Favorites"
            add(SheetAction(favoriteLabel) {
                viewModelScope.launch {
                    if (inFavorites) removeItemFromParent(item, TAG.FAV) else addItemToFavorites(item)
                }
            })
        } else {
            add(SheetAction("Remove from ${parent.name}") {
                viewModelScope.launch { removeItemFromParent(item, parent.id) }
            })
            add(SheetAction("Manage ${parent.name}") {
                viewModelScope.launch {
                    getTag(parent.id).first { it != null }
                    onNavigate(View.ManageTag(parent, parent.items))
                }
            })
        }

        if (item is LauncherItem.Tag) {
            add(SheetAction("Manage ${item.name}") {
                viewModelScope.launch {
                    onNavigate(View.ManageTag(item, item.items))
                }
            })
        } else {
            add(SheetAction("Create Tag") {
                viewModelScope.launch { createTagFromItem(item) }
            })
        }

        val terminal = resolveTerminalItem(item)
        if (terminal is LauncherItem.App) {
            add(SheetAction("App Settings") { openAppSettings(terminal.info) })
            add(SheetAction("Uninstall") { uninstallApp(terminal.info) })
        }
    }

    private suspend fun removeItemFromParent(item: LauncherItem, parentId: Long) {
        val allItems = tagItemDao.getItemsForTag(parentId).first()
        val toDelete = allItems.find { entity ->
            entityMatchesItem(entity, item)
        }
        toDelete?.let { tagItemDao.delete(it) }
    }

    private suspend fun isInFavorites(item: LauncherItem): Boolean {
        val favs = tagItemDao.getItemsForTag(TAG.FAV).first()
        return favs.any { entityMatchesItem(it, item) }
    }

    private fun entityMatchesItem(entity: TagItemEntity, item: LauncherItem): Boolean = when (item) {
        is LauncherItem.App -> {
            entity.type == TagItemType.APP && entity.packageName == item.info.componentName.packageName
        }
        is LauncherItem.Shortcut -> {
            entity.type == TagItemType.SHORTCUT &&
                entity.packageName == item.info.`package` &&
                entity.shortcutId == item.info.id
        }
        is LauncherItem.Tag -> entity.type == TagItemType.TAG && entity.targetTagId == item.id
        is LauncherItem.Placeholder -> false
    }

    private suspend fun addItemToFavorites(item: LauncherItem) {
        val favs = tagItemDao.getItemsForTag(TAG.FAV).first()
        when (item) {
            is LauncherItem.App -> {
                val exists = favs.any {
                    it.type == TagItemType.APP && it.packageName == item.info.componentName.packageName
                }
                if (!exists) {
                    val nextOrder = tagItemDao.nextOrderForTag(TAG.FAV)
                    tagItemDao.insert(
                        TagItemEntity(
                            TAG.FAV,
                            nextOrder,
                            TagItemType.APP,
                            item.info.componentName.packageName
                        )
                    )
                }
            }
            is LauncherItem.Shortcut -> {
                val exists = favs.any {
                    it.type == TagItemType.SHORTCUT &&
                        it.packageName == item.info.`package` &&
                        it.shortcutId == item.info.id
                }
                if (!exists) {
                    val nextOrder = tagItemDao.nextOrderForTag(TAG.FAV)
                    tagItemDao.insert(
                        TagItemEntity(
                            TAG.FAV,
                            nextOrder,
                            TagItemType.SHORTCUT,
                            item.info.`package`,
                            item.info.id,
                            labelOverride = item.label
                        )
                    )
                }
            }
            is LauncherItem.Tag -> {
                val exists = favs.any {
                    it.type == TagItemType.TAG && it.targetTagId == item.id
                }
                if (!exists) {
                    val nextOrder = tagItemDao.nextOrderForTag(TAG.FAV)
                    tagItemDao.insert(
                        TagItemEntity(
                            TAG.FAV,
                            nextOrder,
                            TagItemType.TAG,
                            targetTagId = item.id
                        )
                    )
                }
            }
            is LauncherItem.Placeholder -> Unit
        }
    }

    private suspend fun createTagFromItem(item: LauncherItem) {
        val base = when (item) {
            is LauncherItem.App -> item
            is LauncherItem.Shortcut -> {
                val appInfo = apps.value.find { it.componentName.packageName == item.info.`package` } ?: return
                LauncherItem.App(appInfo, appInfo.label.toString(), appInfo.getIcon(0).toBitmap().asImageBitmap())
            }
            is LauncherItem.Tag -> return
            is LauncherItem.Placeholder -> return
        }
        val newTagId = tagDao.insert(TagEntity(name = "${base.label} Tag"))
        val appEntity = TagItemEntity(newTagId, 0, TagItemType.APP, base.info.componentName.packageName)
        val systemShortcuts = resolveShortcutsForPackage(base.info.componentName.packageName)
        val shortcutEntities = systemShortcuts.mapIndexed { idx, info ->
            TagItemEntity(
                newTagId,
                idx + 1,
                TagItemType.SHORTCUT,
                info.`package`,
                info.id,
                labelOverride = info.shortLabel?.toString()
            )
        }
        tagItemDao.insertAll(listOf(appEntity) + shortcutEntities)
        val nextOrder = tagItemDao.nextOrderForTag(TAG.FAV)
        tagItemDao.insert(TagItemEntity(TAG.FAV, nextOrder, TagItemType.TAG, targetTagId = newTagId))
        _toast.emit("Tag created and added to Favorites")
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

    suspend fun updateOrder(tagId: Long, items: List<LauncherItem>) {
        val currentEntities = tagItemDao.getItemsForTag(tagId).first()

        val appMap = currentEntities
            .asSequence()
            .filter { it.type == TagItemType.APP }
            .filter { it.packageName != null }
            .associateBy { it.packageName!! }
        val shortcutMap = currentEntities
            .asSequence()
            .filter { it.type == TagItemType.SHORTCUT }
            .filter { it.packageName != null && it.shortcutId != null }
            .associateBy { it.packageName!! to it.shortcutId!! }
        val tagMap = currentEntities
            .asSequence()
            .filter { it.type == TagItemType.TAG }
            .filter { it.targetTagId != null }
            .associateBy { it.targetTagId!! }

        val newEntities = items.mapIndexed { index, item ->
            val entity = when (item) {
                is LauncherItem.App -> appMap[item.info.componentName.packageName]
                is LauncherItem.Shortcut -> shortcutMap[item.info.`package` to item.info.id]
                is LauncherItem.Tag -> tagMap[item.id]
                is LauncherItem.Placeholder -> null
            }
            entity?.copy(itemOrder = index)
        }.filterNotNull()

        tagItemDao.updateOrder(tagId, newEntities)
    }

    suspend fun ensureItemsInTag(tagId: Long, items: List<LauncherItem>) {
        val currentEntities = tagItemDao.getItemsForTag(tagId).first()
        val appSet = currentEntities
            .asSequence()
            .filter { it.type == TagItemType.APP }
            .mapNotNull { it.packageName }
            .toMutableSet()
        val shortcutSet = currentEntities
            .asSequence()
            .filter { it.type == TagItemType.SHORTCUT }
            .mapNotNull { entity ->
                val pkg = entity.packageName
                val id = entity.shortcutId
                if (pkg == null || id == null) null else pkg to id
            }
            .toMutableSet()
        val tagSet = currentEntities
            .asSequence()
            .filter { it.type == TagItemType.TAG }
            .mapNotNull { it.targetTagId }
            .toMutableSet()

        items.forEach { item ->
            when (item) {
                is LauncherItem.App -> {
                    val pkg = item.info.componentName.packageName
                    if (appSet.add(pkg)) {
                        val nextOrder = tagItemDao.nextOrderForTag(tagId)
                        tagItemDao.insert(
                            TagItemEntity(
                                tagId,
                                nextOrder,
                                TagItemType.APP,
                                packageName = pkg
                            )
                        )
                    }
                }
                is LauncherItem.Shortcut -> {
                    val key = item.info.`package` to item.info.id
                    if (shortcutSet.add(key)) {
                        val nextOrder = tagItemDao.nextOrderForTag(tagId)
                        tagItemDao.insert(
                            TagItemEntity(
                                tagId,
                                nextOrder,
                                TagItemType.SHORTCUT,
                                packageName = item.info.`package`,
                                shortcutId = item.info.id,
                                labelOverride = item.label
                            )
                        )
                    }
                }
                is LauncherItem.Tag -> {
                    if (tagSet.add(item.id)) {
                        val nextOrder = tagItemDao.nextOrderForTag(tagId)
                        tagItemDao.insert(
                            TagItemEntity(
                                tagId,
                                nextOrder,
                                TagItemType.TAG,
                                targetTagId = item.id
                            )
                        )
                    }
                }
                is LauncherItem.Placeholder -> Unit
            }
        }
    }

    private fun appsToRows(list: List<LauncherActivityInfo>) = list.map {
        LauncherItem.App(it, it.label.toString(), it.getIcon(0).toBitmap().asImageBitmap())
    }
}
