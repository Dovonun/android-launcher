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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
        val representative: LauncherItem?
    ) : LauncherItem {
        val items: List<LauncherItem> by lazy { getItems() }
        override val label: String get() = representative?.label ?: name
        override val icon: ImageBitmap? get() = representative?.icon
    }

    data class Recursion(
        override val label: String = "Recursive Loop",
        override val icon: ImageBitmap? = null
    ) : LauncherItem
}

sealed interface SheetItem {
    data class Header(val title: String, val icon: ImageBitmap? = null) : SheetItem
    data object Divider : SheetItem
    data class Action(val label: String, val onTap: () -> Unit) : SheetItem
}

typealias App = LauncherActivityInfo
typealias Shortcut = ShortcutInfo

object TAG {
    const val FAV: Long = 1
    const val PINNED: Long = 2
}

suspend fun ensureSystemTags(tagDao: TagDao) {
    val existing = tagDao.getAll()
    listOf(
        TagEntity(TAG.FAV, "Favorite"), TagEntity(TAG.PINNED, "Pinned")
    ).filterNot { tag -> existing.any { it.id == tag.id && it.name == tag.name } }
        .forEach { tagDao.insert(it) }
}

fun createCallback(cb: () -> Unit, cleanup: (String) -> Unit) = object : LauncherApps.Callback() {
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
    ) = cleanup(packageName)
}

class AppsVM(application: Application) : AndroidViewModel(application) {
    val db = (application as NiLauncher).database
    private val tagDao = db.tagDao()
    private val tagItemDao = db.tagItemDao()
    private val launcherApps: LauncherApps = application.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = android.os.Process.myUserHandle()
    private val apps = MutableStateFlow<List<LauncherActivityInfo>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cachedShortcuts = tagItemDao.getDistinctPackages().mapLatest { pkgs ->
        coroutineScope {
            pkgs.associateWith { pkg ->
                async {
                    launcherApps.getShortcuts(
                        ShortcutQuery().apply {
                            setPackage(pkg)
                            setQueryFlags(
                                ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST
                            )
                        }, user
                    ).orEmpty()
                }
            }.mapValues { it.value.await() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun refreshApps() = apps.update { launcherApps.getActivityList(null, user) }
    private fun cleanup(pkg: String) { /* TODO */ }

    init {
        viewModelScope.launch(Dispatchers.IO) { ensureSystemTags(tagDao) }
        launcherApps.registerCallback(createCallback(::refreshApps, ::cleanup))
        refreshApps()
    }

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
        val repCache = mutableMapOf<Long, LauncherItem?>()

        fun getRepresentative(tagId: Long, visited: Set<Long>): LauncherItem? {
            // 1. Cycle Detection
            if (tagId in visited) return LauncherItem.Recursion()

            // 2. Check if we've already computed this tag in this pass
            repCache[tagId]?.let { return it }

            // 3. Find the first item
            val firstItem = itemsByTag[tagId]?.firstOrNull() ?: return null

            // 4. Resolve it
            val result = when (firstItem.type) {
                TagItemType.APP, TagItemType.SHORTCUT -> resolveEntity(firstItem)
                TagItemType.TAG -> firstItem.targetTagId?.let { targetId ->
                    getRepresentative(targetId, visited + tagId)
                }
            }

            // 5. Store and return
            if (result !is LauncherItem.Recursion) repCache[tagId] = result
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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

    val favorites = graph.map { it[TAG.FAV]?.items ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun getTag(id: Long): Flow<LauncherItem.Tag?> = graph.map { it[id] }

    private val _toast = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val toast: kotlinx.coroutines.flow.SharedFlow<String> = _toast

    fun launch(item: LauncherItem) {
        viewModelScope.launch {
            when (item) {
                is LauncherItem.App -> launcherApps.startMainActivity(item.info.componentName, user, null, null)
                is LauncherItem.Shortcut -> launcherApps.startShortcut(item.info.`package`, item.info.id, null, null, user)
                is LauncherItem.Tag -> {
                    if (item.items.isEmpty()) {
                        _toast.emit("This tag is empty")
                    } else {
                        launch(item.items[0])
                    }
                }
                is LauncherItem.Recursion -> _toast.emit("Infinite loop detected!")
            }
        }
    }

    suspend fun popupEntries(item: LauncherItem): List<LauncherItem> = when (item) {
        is LauncherItem.Tag -> item.items.drop(1)
        is LauncherItem.Shortcut -> emptyList()
        is LauncherItem.Recursion -> emptyList()
        is LauncherItem.App -> {
            val pkg = item.info.componentName.packageName
            val shortcuts = cachedShortcuts.value[pkg] ?: emptyList()
            shortcuts.map { LauncherItem.Shortcut(it, it.shortLabel.toString(), launcherApps.getShortcutIconDrawable(it, 0)?.toBitmap()?.asImageBitmap()) }
        }
    }

    suspend fun sheetEntries(
        item: LauncherItem,
        parent: LauncherItem.Tag? = null,
        onNavigate: (View) -> Unit = {}
    ): List<SheetItem> = buildList {
        // 1. Tag Section (if the item itself is a tag)
        if (item is LauncherItem.Tag) {
            add(SheetItem.Header("Tag - ${item.name}"))
            add(SheetItem.Action("Manage ${item.name}") { onNavigate(View.ManageTag(item.id, item.name)) })
            add(SheetItem.Action("Delete Tag") {
                viewModelScope.launch { tagDao.delete(TagEntity(item.id, item.name)) }
            })
            add(SheetItem.Divider)
        }

        // 2. Parent Section (where we are currently looking at the item)
        parent?.let { p ->
            val parentLabel = if (p.id == TAG.FAV) "Favorites" else p.name
            add(SheetItem.Header("In $parentLabel"))
            add(SheetItem.Action("Manage $parentLabel") { onNavigate(View.ManageTag(p.id, p.name)) })
            add(SheetItem.Action("Remove from $parentLabel") {
                viewModelScope.launch {
                    val allItems = tagItemDao.getAllItemsFlow().first().filter { it.tagId == p.id }
                    val toDelete = allItems.find { entity ->
                        when (item) {
                            is LauncherItem.App -> entity.type == TagItemType.APP && entity.packageName == item.info.componentName.packageName
                            is LauncherItem.Shortcut -> entity.type == TagItemType.SHORTCUT && entity.packageName == item.info.`package` && entity.shortcutId == item.info.id
                            is LauncherItem.Tag -> entity.type == TagItemType.TAG && entity.targetTagId == item.id
                            is LauncherItem.Recursion -> false
                        }
                    }
                    toDelete?.let { tagItemDao.delete(it) }
                }
            })
            add(SheetItem.Divider)
        }

        // 3. Representative Section (The App/Shortcut itself)
        val representative = if (item is LauncherItem.Tag) item.representative else item
        when (representative) {
            is LauncherItem.App -> {
                add(SheetItem.Header(representative.label, representative.icon))
                
                // Add to Favorites (Global)
                val favs = tagItemDao.getItemsForTag(TAG.FAV).first()
                val isGlobalFav = favs.any { it.type == TagItemType.APP && it.packageName == representative.info.componentName.packageName }
                
                if (!isGlobalFav) {
                    add(SheetItem.Action("Add to Favorites") {
                        viewModelScope.launch {
                            tagItemDao.insert(TagItemEntity(TAG.FAV, favs.size, TagItemType.APP, representative.info.componentName.packageName))
                        }
                    })
                }

                add(SheetItem.Action("Create Tag") {
                    viewModelScope.launch {
                        val newTagId = tagDao.insert(TagEntity(name = "${representative.label} Tag"))
                        val appEntity = TagItemEntity(newTagId, 0, TagItemType.APP, representative.info.componentName.packageName)
                        val systemShortcuts = cachedShortcuts.value[representative.info.componentName.packageName].orEmpty()
                        val shortcutEntities = systemShortcuts.mapIndexed { idx, info ->
                            TagItemEntity(newTagId, idx + 1, TagItemType.SHORTCUT, info.`package`, info.id, labelOverride = info.shortLabel?.toString())
                        }
                        tagItemDao.insertAll(listOf(appEntity) + shortcutEntities)
                        
                        // Add this new tag to favorites
                        val favsCount = tagItemDao.getItemsForTag(TAG.FAV).first().size
                        tagItemDao.insert(TagItemEntity(TAG.FAV, favsCount, TagItemType.TAG, targetTagId = newTagId))
                        
                        _toast.emit("Tag created and added to Favorites")
                    }
                })

                add(SheetItem.Action("Open Settings") {
                    launcherApps.startAppDetailsActivity(representative.info.componentName, user, null, null)
                })
                add(SheetItem.Action("Uninstall") {
                    val context = getApplication<Application>()
                    context.startActivity(Intent(Intent.ACTION_DELETE).apply {
                        data = "package:${representative.info.componentName.packageName}".toUri()
                    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
            is LauncherItem.Shortcut -> {
                add(SheetItem.Header(representative.label, representative.icon))
                
                val favs = tagItemDao.getItemsForTag(TAG.FAV).first()
                val isGlobalFav = favs.any { it.type == TagItemType.SHORTCUT && it.packageName == representative.info.`package` && it.shortcutId == representative.info.id }
                
                if (!isGlobalFav) {
                    add(SheetItem.Action("Add to Favorites") {
                        viewModelScope.launch {
                            tagItemDao.insert(TagItemEntity(TAG.FAV, favs.size, TagItemType.SHORTCUT, representative.info.`package`, representative.info.id, labelOverride = representative.label))
                        }
                    })
                }

                add(SheetItem.Action("Create Tag") {
                    viewModelScope.launch {
                        val newTagId = tagDao.insert(TagEntity(name = "${representative.label} Tag"))
                        val shortcutEntity = TagItemEntity(newTagId, 0, TagItemType.SHORTCUT, representative.info.`package`, representative.info.id, labelOverride = representative.label)
                        tagItemDao.insert(shortcutEntity)
                        
                        // Add this new tag to favorites
                        val favsCount = tagItemDao.getItemsForTag(TAG.FAV).first().size
                        tagItemDao.insert(TagItemEntity(TAG.FAV, favsCount, TagItemType.TAG, targetTagId = newTagId))
                        
                        _toast.emit("Tag created and added to Favorites")
                    }
                })

                add(SheetItem.Action("Open Settings") {
                    launcherApps.startAppDetailsActivity(representative.info.activity, user, null, null)
                })
            }
            else -> {}
        }
    }

    suspend fun updateOrder(tagId: Long, items: List<LauncherItem>) {
        val currentEntities = tagItemDao.getAllItemsFlow().first().filter { it.tagId == tagId }
        
        val newEntities = items.mapIndexed { index, item ->
            val entity = when (item) {
                is LauncherItem.App -> currentEntities.find { it.type == TagItemType.APP && it.packageName == item.info.componentName.packageName }
                is LauncherItem.Shortcut -> currentEntities.find { it.type == TagItemType.SHORTCUT && it.packageName == item.info.`package` && it.shortcutId == item.info.id }
                is LauncherItem.Tag -> currentEntities.find { it.type == TagItemType.TAG && it.targetTagId == item.id }
                is LauncherItem.Recursion -> null
            }
            entity?.copy(itemOrder = index)
        }.filterNotNull()

        tagItemDao.updateOrder(tagId, newEntities)
    }

    private fun appsToRows(list: List<LauncherActivityInfo>) = list.map {
        LauncherItem.App(it, it.label.toString(), it.getIcon(0).toBitmap().asImageBitmap())
    }
}