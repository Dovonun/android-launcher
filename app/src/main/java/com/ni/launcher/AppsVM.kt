package com.ni.launcher

import android.app.Application
import android.content.ActivityNotFoundException
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
import androidx.room.withTransaction
import com.ni.launcher.data.TagDao
import com.ni.launcher.data.TagEntity
import com.ni.launcher.data.TagItemDao
import com.ni.launcher.data.TagItemEntity
import com.ni.launcher.data.TagItemType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

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

private data class LauncherGraph(
    val tags: Map<Long, LauncherItem.Tag> = emptyMap(),
    val apps: List<LauncherItem.App> = emptyList(),
    val appShortcuts: Map<String, List<LauncherItem.Shortcut>> = emptyMap()
)

object TAG {
    const val FAV: Long = 1
    const val PINNED: Long = 2
    // User tags already occupy positive auto-generated IDs in existing databases.
    const val HIDDEN: Long = -1

    fun isSystem(id: Long): Boolean = id == FAV || id == PINNED || id == HIDDEN
}

private const val PERSIST_DEBOUNCE_MS = 150L

suspend fun ensureSystemTags(tagDao: TagDao) {
    val existing = tagDao.getAllFlow().first()
    listOf(
        TagEntity(TAG.FAV, "Favorites"), TagEntity(TAG.PINNED, "Pinned"), TagEntity(TAG.HIDDEN, "Hidden")
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
    private val tagState = MutableStateFlow<List<TagEntity>>(emptyList())
    private val tagItemState = MutableStateFlow<List<TagItemEntity>>(emptyList())
    private val tagDataReady = CompletableDeferred<Unit>()
    private val pendingTagItems = mutableSetOf<Long>()
    private val pendingTags = mutableSetOf<Long>()
    private val persistJobs = mutableMapOf<Long, Job>()

    private sealed interface TagItemKey {
        data class App(val pkg: String) : TagItemKey
        data class Shortcut(val pkg: String, val id: String) : TagItemKey
        data class Tag(val id: Long) : TagItemKey
    }

    private fun itemKey(item: LauncherItem): TagItemKey? = when (item) {
        is LauncherItem.App -> TagItemKey.App(item.info.componentName.packageName)
        is LauncherItem.Shortcut -> TagItemKey.Shortcut(item.info.`package`, item.info.id)
        is LauncherItem.Tag -> TagItemKey.Tag(item.id)
        is LauncherItem.Placeholder -> null
    }

    private fun currentTagItems(tagId: Long): List<TagItemEntity> =
        tagItemState.value.filter { it.tagId == tagId }.sortedBy { it.itemOrder }

    private fun setTagItems(tagId: Long, items: List<TagItemEntity>) {
        tagItemState.update { existing ->
            (existing.filterNot { it.tagId == tagId } + items)
                .sortedWith(compareBy<TagItemEntity> { it.tagId }.thenBy { it.itemOrder })
        }
    }

    private fun setTags(tags: List<TagEntity>) {
        tagState.value = tags.sortedBy { it.id }
    }

    private fun buildEntitiesForOrder(
        tagId: Long,
        items: List<LauncherItem>,
        currentEntities: List<TagItemEntity>
    ): List<TagItemEntity> {
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

        return items.mapIndexed { index, item ->
            val entity = when (item) {
                is LauncherItem.App -> appMap[item.info.componentName.packageName]
                is LauncherItem.Shortcut -> shortcutMap[item.info.`package` to item.info.id]
                is LauncherItem.Tag -> tagMap[item.id]
                is LauncherItem.Placeholder -> null
            }
            entity?.copy(itemOrder = index)
        }.filterNotNull()
    }

    private fun mergeTagsFromDb(dbTags: List<TagEntity>) {
        val ram = tagState.value
        val ramById = ram.associateBy { it.id }
        val dbById = dbTags.associateBy { it.id }
        val pending = synchronized(pendingTags) { pendingTags.toSet() }
        val merged = mutableListOf<TagEntity>()
        for (dbTag in dbTags) {
            if (pending.contains(dbTag.id)) {
                ramById[dbTag.id]?.let { merged.add(it) }
            } else {
                merged.add(dbTag)
            }
        }
        for (ramTag in ram) {
            if (ramTag.id !in dbById && pending.contains(ramTag.id)) {
                merged.add(ramTag)
            }
        }
        val toClear = mutableSetOf<Long>()
        for (tagId in pending) {
            val ramTag = ramById[tagId]
            val dbTag = dbById[tagId]
            val matches = when {
                ramTag == null && dbTag == null -> true
                ramTag != null && dbTag != null && ramTag.name == dbTag.name -> true
                else -> false
            }
            if (matches) toClear.add(tagId)
        }
        if (toClear.isNotEmpty()) {
            synchronized(pendingTags) { pendingTags.removeAll(toClear) }
        }
        setTags(merged)
    }

    private fun mergeTagItemsFromDb(dbItems: List<TagItemEntity>) {
        val ram = tagItemState.value
        val ramByTag = ram.groupBy { it.tagId }.mapValues { it.value.sortedBy { item -> item.itemOrder } }
        val dbByTag = dbItems.groupBy { it.tagId }.mapValues { it.value.sortedBy { item -> item.itemOrder } }
        val tagIds = (ramByTag.keys + dbByTag.keys).toSet()
        val pending = synchronized(pendingTagItems) { pendingTagItems.toSet() }
        val merged = mutableListOf<TagItemEntity>()
        val toClear = mutableSetOf<Long>()
        for (tagId in tagIds) {
            val ramItems = ramByTag[tagId].orEmpty()
            val dbItemsForTag = dbByTag[tagId].orEmpty()
            if (pending.contains(tagId)) {
                merged.addAll(ramItems)
                if (ramItems == dbItemsForTag) {
                    toClear.add(tagId)
                }
            } else {
                merged.addAll(dbItemsForTag)
            }
        }
        if (toClear.isNotEmpty()) {
            synchronized(pendingTagItems) { pendingTagItems.removeAll(toClear) }
        }
        tagItemState.value =
            merged.sortedWith(compareBy<TagItemEntity> { it.tagId }.thenBy { it.itemOrder })
    }

    private fun schedulePersist(tagId: Long) {
        synchronized(pendingTagItems) { pendingTagItems.add(tagId) }
        synchronized(persistJobs) { persistJobs[tagId]?.cancel() }
        val job = viewModelScope.launch(Dispatchers.IO) {
            delay(PERSIST_DEBOUNCE_MS)
            val snapshot = currentTagItems(tagId)
            tagItemDao.updateOrder(tagId, snapshot)
        }
        job.invokeOnCompletion { synchronized(persistJobs) { persistJobs.remove(tagId) } }
        synchronized(persistJobs) { persistJobs[tagId] = job }
    }

    private fun cancelPersist(tagId: Long) {
        synchronized(persistJobs) {
            persistJobs[tagId]?.cancel()
            persistJobs.remove(tagId)
        }
    }

    // Query all visible packages, including pins that have no local row yet.
    // null means the initial platform query has not completed.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val cachedShortcuts: StateFlow<Map<String, List<ShortcutInfo>>?> =
        shortcutsRefreshTick.mapLatest {
            withContext(Dispatchers.IO) {
                launcherApps.getShortcuts(
                    ShortcutQuery().apply {
                        setQueryFlags(
                            ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST
                        )
                    },
                    user
                ).orEmpty().groupBy { it.`package` }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun refreshApps() {
        val latest = launcherApps.getActivityList(null, user)
        apps.update { latest }
        // Keep shortcut cache in sync on package add/remove/change callbacks.
        shortcutsRefreshTick.update { it + 1 }
    }
    private fun refreshShortcuts() {
        // Shortcut changes do not necessarily change the installed app list.
        shortcutsRefreshTick.update { it + 1 }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { ensureSystemTags(tagDao) }
        viewModelScope.launch(Dispatchers.IO) {
            combine(tagDao.getAllFlow(), tagItemDao.getAllItemsFlow()) { tags, items -> tags to items }
                .collect { (tags, items) ->
                    mergeTagsFromDb(tags)
                    mergeTagItemsFromDb(items)
                    tagDataReady.complete(Unit)
                }
        }
        launcherApps.registerCallback(createCallback(::refreshApps) { refreshShortcuts() })
        refreshApps()
    }

    // Reactive graph that drives app/tag UI and badges.
    private val graph = combine(
        apps, cachedShortcuts.filterNotNull(), tagState, tagItemState
    ) { apps, shortcuts, tagEntities, itemEntities ->
        val shortcutInfos = shortcuts.values.flatten().associateBy { ShortcutKey(it.`package`, it.id) }
        val descriptors = shortcutInfos.values.map {
            ShortcutDescriptor(ShortcutKey(it.`package`, it.id), it.isDeclaredInManifest, it.isDynamic, it.isPinned, it.rank)
        }
        val itemsByTag = itemEntities.groupBy { it.tagId }.toMutableMap()
        // These transient rows are visible to Manage. Its normal save path persists their itemOrder.
        itemsByTag[TAG.PINNED] = appendUnmanagedShortcuts(
            TAG.PINNED,
            itemsByTag[TAG.PINNED].orEmpty(),
            descriptors.filter { it.isPinned }
        )
        val appRows = appsToRows(apps)
        val appsByPackage = appRows.distinctBy { it.info.componentName.packageName }
            .associateBy { it.info.componentName.packageName }
        val resolvedShortcuts = mutableMapOf<Pair<ShortcutKey, String?>, LauncherItem.Shortcut>()

        // --- Layer 1: Universal Resolver ---
        // One place to turn a DB entity into a UI object
        fun resolveEntity(entity: TagItemEntity): LauncherItem? = when (entity.type) {
            TagItemType.APP -> appsByPackage[entity.packageName]?.let {
                if (entity.labelOverride == null) it else it.copy(label = entity.labelOverride)
            }
            TagItemType.SHORTCUT -> {
                val key = ShortcutKey(entity.packageName.orEmpty(), entity.shortcutId.orEmpty())
                shortcutInfos[key]?.let { info ->
                    resolvedShortcuts.getOrPut(key to entity.labelOverride) {
                        LauncherItem.Shortcut(
                            info,
                            entity.labelOverride ?: info.shortLabel.toString(),
                            launcherApps.getShortcutIconDrawable(info, 0)?.toBitmap()?.asImageBitmap()
                        )
                    }
                }
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
        
        val managedRowsByPkg = managedShortcutRowsByPackage(itemEntities)
        val descriptorsByPkg = descriptors.groupBy { it.key.packageName }
        val appShortcuts = (appsByPackage.keys + shortcuts.keys).associateWith { pkg ->
            appendUnmanagedShortcuts(
                TAG.PINNED,
                managedRowsByPkg[pkg].orEmpty().distinctBy { it.shortcutId },
                descriptorsByPkg[pkg].orEmpty()
            ).mapNotNull { resolveEntity(it) as? LauncherItem.Shortcut }
        }
        // Resolve tag rows on the graph dispatcher too, before sharing this snapshot with the UI.
        finalGraph.values.forEach { it.items }
        LauncherGraph(finalGraph, appRows, appShortcuts)
    }.onStart { tagDataReady.await() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LauncherGraph())

    // Reactive all-apps list for main screen rendering.
    val uiAllGrouped = graph.map { snapshot ->
        val pinned = snapshot.tags[TAG.PINNED]?.items.orEmpty().filterIsInstance<LauncherItem.Shortcut>()
        (snapshot.apps + pinned).sortedBy { it.label.lowercase() }
            .groupBy { it.label.first().uppercaseChar() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyMap())

    // Reactive favorites list for home screen rendering.
    val favorites = graph.map { it.tags[TAG.FAV]?.items ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allTags = graph.map { graphMap ->
        graphMap.tags.values.sortedBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getTag(id: Long): Flow<LauncherItem.Tag?> = graph.map { it.tags[id] }

    fun getTagsForItem(item: LauncherItem): Flow<List<String>> = combine(
        tagState, tagItemState
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
                is LauncherItem.Shortcut -> {
                    val info = target.info
                    if (!info.isEnabled) {
                        _toast.emit(info.disabledMessage?.toString() ?: "Shortcut is disabled")
                        return@launch
                    }
                    try {
                        launcherApps.startShortcut(info.`package`, info.id, null, null, user)
                    } catch (_: ActivityNotFoundException) {
                        _toast.emit("Shortcut could not be started")
                    } catch (_: SecurityException) {
                        _toast.emit("Shortcut permission denied")
                    }
                }
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
            graph.first { pkg in it.appShortcuts }.appShortcuts[pkg].orEmpty()
        }
    }

    // Managed-list SHORTCUT rows grouped by package: PINNED tag first, then other tags by id, each by itemOrder.
    private fun managedShortcutRowsByPackage(itemEntities: List<TagItemEntity>): Map<String, List<TagItemEntity>> =
        itemEntities.asSequence()
            .filter { it.tagId != TAG.HIDDEN && it.type == TagItemType.SHORTCUT && it.packageName != null && it.shortcutId != null }
            .sortedWith(
                compareBy<TagItemEntity> { if (it.tagId == TAG.PINNED) 0 else 1 }
                    .thenBy { it.tagId }
                    .thenBy { it.itemOrder }
            )
            .groupBy { it.packageName!! }

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
        onShortcutRemoval: (LauncherItem.Shortcut, Long) -> Unit,
        onNavigate: (View) -> Unit = {}
    ): List<SheetAction> = buildList {
        if (parent == null) {
            val inFavorites = isInFavorites(item)
            val favoriteLabel = if (inFavorites) "Remove from Favorites" else "Add to Favorites"
            add(SheetAction(favoriteLabel) {
                viewModelScope.launch {
                    if (inFavorites) {
                        if (item is LauncherItem.Shortcut) onShortcutRemoval(item, TAG.FAV)
                        else removeItemFromParent(item, TAG.FAV)
                    } else {
                        val key = itemKey(item)
                        val favTag = getTag(TAG.FAV).first { it != null } ?: return@launch
                        val exists = key != null && favTag.items.any { itemKey(it) == key }
                        val nextItems = if (exists || key == null) favTag.items else favTag.items + item
                        onNavigate(View.ManageTag(favTag, nextItems))
                    }
                }
            })
        } else {
            add(SheetAction("Remove from ${parent.name}") {
                if (item is LauncherItem.Shortcut && parent.id != TAG.HIDDEN) {
                    onShortcutRemoval(item, parent.id)
                } else {
                    viewModelScope.launch { removeItemFromParent(item, parent.id) }
                }
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
                viewModelScope.launch { createTagFromItem(item, onNavigate) }
            })
        }

        val terminal = resolveTerminalItem(item)
        if (terminal is LauncherItem.App) {
            add(SheetAction("App Settings") { openAppSettings(terminal.info) })
            add(SheetAction("Uninstall") { uninstallApp(terminal.info) })
        }
    }

    private suspend fun removeItemFromParent(item: LauncherItem, parentId: Long) {
        val allItems = currentTagItems(parentId)
        val toDelete = allItems.find { entityMatchesItem(it, item) } ?: return
        val nextItems = allItems.filterNot { it == toDelete }
            .mapIndexed { index, entity -> entity.copy(itemOrder = index) }
        setTagItems(parentId, nextItems)
        schedulePersist(parentId)
    }

    // Hide annotates one shortcut and removes the current membership. Unpin is global.
    // Rendering intentionally does not filter Hidden yet.
    internal suspend fun removeShortcutFromTag(
        item: LauncherItem.Shortcut,
        parentId: Long,
        action: ShortcutRemovalAction
    ) {
        tagDataReady.await()
        val pkg = item.info.`package`
        val id = item.info.id
        val ramByTag = tagItemState.value.groupBy { it.tagId }
        val dirtyTags = synchronized(pendingTagItems) { pendingTagItems.toSet() }
        fun matches(row: TagItemEntity): Boolean =
            row.type == TagItemType.SHORTCUT && row.packageName == pkg && row.shortcutId == id

        // Once Android has unpinned an item, finish database cleanup even if its screen closes.
        withContext(Dispatchers.IO + NonCancellable) {
            val updates = shortcutPinMutex.withLock {
                if (action == ShortcutRemovalAction.UNPIN) {
                    // Refresh immediately before replacing the package's complete pin set.
                    val current = launcherApps.getShortcuts(
                        ShortcutQuery().apply {
                            setPackage(pkg)
                            setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
                        }, user
                    ).orEmpty()
                    val target = current.find { it.id == id }
                    check(target == null || requiresUnpinChoice(target.isPinned, target.isDeclaredInManifest, target.isDynamic)) {
                        "This shortcut is published by the app. Hide it instead."
                    }
                    launcherApps.pinShortcuts(pkg, current.filter { it.isPinned && it.id != id }.map { it.id }, user)
                }
                db.withTransaction {
                    val storedByTag = tagItemDao.getAllItems().groupBy { it.tagId }
                    fun rows(tagId: Long): List<TagItemEntity> =
                        if (tagId in dirtyTags) ramByTag[tagId].orEmpty() else storedByTag[tagId].orEmpty()

                    val changed = mutableMapOf<Long, List<TagItemEntity>>()
                    if (action == ShortcutRemovalAction.UNPIN) {
                        for (tagId in storedByTag.keys + ramByTag.keys) {
                            val before = rows(tagId)
                            if (before.any(::matches)) changed[tagId] = before.filterNot(::matches)
                        }
                    } else {
                        tagDao.insert(TagEntity(TAG.HIDDEN, "Hidden"))
                        val hidden = rows(TAG.HIDDEN)
                        changed[TAG.HIDDEN] = if (hidden.any(::matches)) hidden else hidden + TagItemEntity(
                            TAG.HIDDEN,
                            (hidden.maxOfOrNull { it.itemOrder } ?: -1) + 1,
                            TagItemType.SHORTCUT,
                            pkg,
                            id,
                            labelOverride = item.label
                        )
                        if (parentId != TAG.HIDDEN) changed[parentId] = rows(parentId).filterNot(::matches)
                    }
                    val ordered = changed.mapValues { (_, rows) -> rows.mapIndexed { index, row -> row.copy(itemOrder = index) } }
                    ordered.forEach { (tagId, rows) ->
                        cancelPersist(tagId)
                        tagItemDao.updateOrder(tagId, rows)
                    }
                    ordered
                }
            }
            updates.forEach { (tagId, rows) -> setTagItems(tagId, rows) }
            synchronized(pendingTagItems) { pendingTagItems.removeAll(updates.keys) }
            refreshShortcuts()
        }
    }

    private suspend fun isInFavorites(item: LauncherItem): Boolean {
        val favs = currentTagItems(TAG.FAV)
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
        val favs = currentTagItems(TAG.FAV)
        when (item) {
            is LauncherItem.App -> {
                val exists = favs.any {
                    it.type == TagItemType.APP && it.packageName == item.info.componentName.packageName
                }
                if (!exists) {
                    val nextOrder = (favs.maxOfOrNull { it.itemOrder } ?: -1) + 1
                    val next = favs + TagItemEntity(
                        TAG.FAV,
                        nextOrder,
                        TagItemType.APP,
                        item.info.componentName.packageName
                    )
                    setTagItems(TAG.FAV, next)
                    schedulePersist(TAG.FAV)
                }
            }
            is LauncherItem.Shortcut -> {
                val exists = favs.any {
                    it.type == TagItemType.SHORTCUT &&
                        it.packageName == item.info.`package` &&
                        it.shortcutId == item.info.id
                }
                if (!exists) {
                    val nextOrder = (favs.maxOfOrNull { it.itemOrder } ?: -1) + 1
                    val next = favs + TagItemEntity(
                        TAG.FAV,
                        nextOrder,
                        TagItemType.SHORTCUT,
                        item.info.`package`,
                        item.info.id,
                        labelOverride = item.label
                    )
                    setTagItems(TAG.FAV, next)
                    schedulePersist(TAG.FAV)
                }
            }
            is LauncherItem.Tag -> {
                val exists = favs.any {
                    it.type == TagItemType.TAG && it.targetTagId == item.id
                }
                if (!exists) {
                    val nextOrder = (favs.maxOfOrNull { it.itemOrder } ?: -1) + 1
                    val next = favs + TagItemEntity(
                        TAG.FAV,
                        nextOrder,
                        TagItemType.TAG,
                        targetTagId = item.id
                    )
                    setTagItems(TAG.FAV, next)
                    schedulePersist(TAG.FAV)
                }
            }
            is LauncherItem.Placeholder -> Unit
        }
    }

    private suspend fun createTagFromItem(item: LauncherItem, onNavigate: (View) -> Unit) {
        val tagItems = when (item) {
            is LauncherItem.App -> listOf(item) + popupEntriesSnapshot(item)
            is LauncherItem.Shortcut -> listOf(item)
            is LauncherItem.Tag -> return
            is LauncherItem.Placeholder -> return
        }
        val newTagName = "${item.label} Tag"
        val newTagId = tagDao.insert(TagEntity(name = newTagName))
        val newTag = TagEntity(id = newTagId, name = newTagName)
        val tagEntities = tagItems.mapIndexedNotNull { index, entry ->
            when (entry) {
                is LauncherItem.App -> TagItemEntity(
                    newTagId,
                    index,
                    TagItemType.APP,
                    entry.info.componentName.packageName
                )
                is LauncherItem.Shortcut -> TagItemEntity(
                    newTagId,
                    index,
                    TagItemType.SHORTCUT,
                    entry.info.`package`,
                    entry.info.id,
                    labelOverride = entry.label
                )
                is LauncherItem.Tag -> TagItemEntity(
                    newTagId,
                    index,
                    TagItemType.TAG,
                    targetTagId = entry.id
                )
                is LauncherItem.Placeholder -> null
            }
        }
        val favs = currentTagItems(TAG.FAV)
        val nextOrder = (favs.maxOfOrNull { it.itemOrder } ?: -1) + 1
        val favEntity = TagItemEntity(TAG.FAV, nextOrder, TagItemType.TAG, targetTagId = newTagId)

        synchronized(pendingTags) { pendingTags.add(newTagId) }
        synchronized(pendingTagItems) {
            pendingTagItems.add(newTagId)
            pendingTagItems.add(TAG.FAV)
        }
        setTags(tagState.value + newTag)
        setTagItems(newTagId, tagEntities)
        setTagItems(TAG.FAV, favs + favEntity)
        schedulePersist(newTagId)
        schedulePersist(TAG.FAV)
        _toast.emit("Tag created and added to Favorites")

        val representative = tagItems.firstOrNull()
            ?: LauncherItem.Placeholder(PlaceholderKind.EMPTY_TAG, "Empty tag")
        val uiTag = LauncherItem.Tag(
            id = newTagId,
            name = newTagName,
            representative = representative,
            getItems = { tagItems }
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

    suspend fun updateOrder(tagId: Long, items: List<LauncherItem>) {
        val currentEntities = currentTagItems(tagId)
        val newEntities = buildEntitiesForOrder(tagId, items, currentEntities)

        setTagItems(tagId, newEntities)
        schedulePersist(tagId)
    }

    suspend fun ensureItemsInTag(tagId: Long, items: List<LauncherItem>) {
        val currentEntities = currentTagItems(tagId)
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

        var nextOrder = (currentEntities.maxOfOrNull { it.itemOrder } ?: -1) + 1
        val nextEntities = currentEntities.toMutableList()
        items.forEach { item ->
            when (item) {
                is LauncherItem.App -> {
                    val pkg = item.info.componentName.packageName
                    if (appSet.add(pkg)) {
                        nextEntities.add(
                            TagItemEntity(
                                tagId,
                                nextOrder++,
                                TagItemType.APP,
                                packageName = pkg
                            )
                        )
                    }
                }
                is LauncherItem.Shortcut -> {
                    val key = item.info.`package` to item.info.id
                    if (shortcutSet.add(key)) {
                        nextEntities.add(
                            TagItemEntity(
                                tagId,
                                nextOrder++,
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
                        nextEntities.add(
                            TagItemEntity(
                                tagId,
                                nextOrder++,
                                TagItemType.TAG,
                                targetTagId = item.id
                            )
                        )
                    }
                }
                is LauncherItem.Placeholder -> Unit
            }
        }
        setTagItems(tagId, nextEntities.sortedBy { it.itemOrder })
    }

    suspend fun syncTagItemsToList(tagId: Long, items: List<LauncherItem>) {
        ensureItemsInTag(tagId, items)
        val currentEntities = currentTagItems(tagId)
        val entityKeys = currentEntities.mapNotNull { entity ->
            when (entity.type) {
                TagItemType.APP -> entity.packageName?.let { TagItemKey.App(it) }
                TagItemType.SHORTCUT -> {
                    val pkg = entity.packageName
                    val id = entity.shortcutId
                    if (pkg == null || id == null) null else TagItemKey.Shortcut(pkg, id)
                }
                TagItemType.TAG -> entity.targetTagId?.let { TagItemKey.Tag(it) }
            }
        }
        val itemKeys = items.mapNotNull { itemKey(it) }
        if (entityKeys != itemKeys) {
            updateOrder(tagId, items)
            return
        }
        schedulePersist(tagId)
    }

    suspend fun syncTagItemsInMemory(tagId: Long, items: List<LauncherItem>) {
        ensureItemsInTag(tagId, items)
        val currentEntities = currentTagItems(tagId)
        val entityKeys = currentEntities.mapNotNull { entity ->
            when (entity.type) {
                TagItemType.APP -> entity.packageName?.let { TagItemKey.App(it) }
                TagItemType.SHORTCUT -> {
                    val pkg = entity.packageName
                    val id = entity.shortcutId
                    if (pkg == null || id == null) null else TagItemKey.Shortcut(pkg, id)
                }
                TagItemType.TAG -> entity.targetTagId?.let { TagItemKey.Tag(it) }
            }
        }
        val itemKeys = items.mapNotNull { itemKey(it) }
        if (entityKeys != itemKeys) {
            val newEntities = buildEntitiesForOrder(tagId, items, currentEntities)
            setTagItems(tagId, newEntities)
        }
        synchronized(pendingTagItems) { pendingTagItems.add(tagId) }
    }

    suspend fun flushTagItems(tagId: Long) {
        synchronized(pendingTagItems) { pendingTagItems.add(tagId) }
        cancelPersist(tagId)
        val snapshot = currentTagItems(tagId)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            tagItemDao.updateOrder(tagId, snapshot)
        }
    }

    suspend fun renameTag(tagId: Long, name: String) {
        if (TAG.isSystem(tagId)) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = tagState.value
        val existing = current.firstOrNull { it.id == tagId } ?: return
        if (existing.name == trimmed) return
        setTags(current.map { if (it.id == tagId) it.copy(name = trimmed) else it })
        synchronized(pendingTags) { pendingTags.add(tagId) }
        viewModelScope.launch(Dispatchers.IO) {
            tagDao.update(existing.copy(name = trimmed))
        }
    }

    suspend fun deleteTag(tagId: Long) {
        if (TAG.isSystem(tagId)) return
        val current = tagState.value
        val existing = current.firstOrNull { it.id == tagId } ?: return
        setTags(current.filterNot { it.id == tagId })
        setTagItems(tagId, emptyList())
        synchronized(pendingTags) { pendingTags.add(tagId) }
        synchronized(pendingTagItems) { pendingTagItems.add(tagId) }
        viewModelScope.launch(Dispatchers.IO) {
            tagDao.delete(existing)
        }
    }

    private fun appsToRows(list: List<LauncherActivityInfo>) = list.map {
        LauncherItem.App(it, it.label.toString(), it.getIcon(0).toBitmap().asImageBitmap())
    }
}
