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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Tag(val id: Long)
data class UiRow(val label: String, val icon: ImageBitmap?, val item: Any)
data class SheetRow(val label: String, val onTap: () -> Unit)
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
    // THOUGHT: Could I cache all shortcuts?
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
    private fun cleanup(pkg: String) { /*db.taggedAppDao()*/ }
    // TODO: delete all tags for this app on uninstall

    init {
        viewModelScope.launch(Dispatchers.IO) { ensureSystemTags(tagDao) }
        launcherApps.registerCallback(createCallback(::refreshApps, ::cleanup))
        refreshApps()
    }

    val uiAllGrouped = combine(
        apps, uiList(TAG.PINNED)
    ) { apps, pinned ->
        val uiApps = appsToRows(apps)
        (uiApps + pinned).sortedBy { it.label.lowercase() }
            .groupBy { it.label.first().uppercaseChar() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyMap())

    val favorites = uiList(TAG.FAV).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    @OptIn(ExperimentalCoroutinesApi::class)
    fun uiList(tagId: Long): Flow<List<UiRow>> = tagItemDao.getItemsForTag(tagId).flatMapLatest { items ->
        val flows = items.map { item ->
            when (item.type) {
                TagItemType.APP -> apps.map { list ->
                    list.find { it.componentName.packageName == item.packageName }?.let { app ->
                        UiRow(
                            item.labelOverride ?: app.label.toString(),
                            app.getIcon(0).toBitmap().asImageBitmap(),
                            item // Pass the entity so we can reorder it later
                        )
                    }
                }

                TagItemType.SHORTCUT -> cachedShortcuts.map { shortcuts ->
                    shortcuts[item.packageName]?.find { it.id == item.shortcutId }?.let { shortcut ->
                        UiRow(
                            item.labelOverride ?: shortcut.shortLabel.toString(),
                            launcherApps.getShortcutIconDrawable(shortcut, 0)?.toBitmap()
                                ?.asImageBitmap(),
                            item // Pass the entity
                        )
                    }
                }

                TagItemType.TAG -> {
                    val targetId = item.targetTagId ?: 0L
                    uiList(targetId).map { children ->
                        val representative = children.firstOrNull()
                        UiRow(
                            item.labelOverride ?: representative?.label ?: "Empty Folder",
                            representative?.icon,
                            item // Pass the entity
                        )
                    }
                }
            }
        }
        if (flows.isEmpty()) flowOf(emptyList())
        else combine(flows) { it.filterNotNull().toList() }
    }

    fun launch(item: Any) {
        viewModelScope.launch {
            val resolved = if (item is TagItemEntity) resolveItem(item) else item
            when (val i = resolved) {
                is App -> launcherApps.startMainActivity(i.componentName, user, null, null)
                is Shortcut -> launcherApps.startShortcut(i.`package`, i.id, null, null, user)
                is Tag -> {
                    val children = uiList(i.id).first()
                    children.firstOrNull()?.let { representative ->
                        launch(representative.item)
                    }
                }
            }
        }
    }

    suspend fun updateOrder(tagId: Long, items: List<UiRow>) {
        val entities = items.mapNotNull { it.item as? TagItemEntity }
        tagItemDao.updateOrder(tagId, entities)
    }

    suspend fun deleteTag(tagId: Long) {
        tagDao.delete(TagEntity(id = tagId, name = ""))
    }

    suspend fun getOrCreatePopupTag(item: TagItemEntity): Long {
        if (item.type == TagItemType.TAG) return item.targetTagId ?: 0L

        // Create a new tag for this item's popup
        val newTagId = tagDao.insert(TagEntity(name = "Custom Popup"))
        
        // 1. Copy the item as the representative (order 0)
        val representative = item.copy(tagId = newTagId, itemOrder = 0)
        
        // 2. Fetch system shortcuts for this app
        val pkg = item.packageName ?: ""
        val systemShortcuts = launcherApps.getShortcuts(
            ShortcutQuery().apply {
                setPackage(pkg)
                setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
            }, user
        ).orEmpty()
        
        val shortcutItems = systemShortcuts.mapIndexed { index, info ->
            TagItemEntity(
                tagId = newTagId,
                itemOrder = index + 1,
                type = TagItemType.SHORTCUT,
                packageName = pkg,
                shortcutId = info.id,
                labelOverride = info.shortLabel?.toString()
            )
        }
        
        tagItemDao.insertAll(listOf(representative) + shortcutItems)
        
        // 3. Replace the original item in its parent tag with a reference to the new tag
        tagItemDao.insert(item.copy(type = TagItemType.TAG, targetTagId = newTagId, packageName = null, shortcutId = null))
        
        return newTagId
    }

    suspend fun resolveItem(item: TagItemEntity): Any? = when (item.type) {
        TagItemType.APP -> apps.value.find { it.componentName.packageName == item.packageName }
        TagItemType.SHORTCUT -> cachedShortcuts.value[item.packageName]?.find { it.id == item.shortcutId }
        TagItemType.TAG -> Tag(item.targetTagId ?: 0L)
    }

    suspend fun popupEntries(item: Any): List<UiRow> {
        val resolved = if (item is TagItemEntity) resolveItem(item) else item
        return when (resolved) {
            is Tag -> uiList(resolved.id).first().drop(1)
            is Shortcut -> emptyList()
            is App -> {
                val pkg = resolved.componentName.packageName
                val cache = cachedShortcuts.value
                val shortcuts = cache.getOrElse(pkg) {
                    launcherApps.getShortcuts(
                        ShortcutQuery().apply {
                            setPackage(pkg)
                            setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
                        }, user
                    ).orEmpty()
                }
                shortcutsToRows(shortcuts)
            }

            else -> emptyList()
        }
    }

    suspend fun sheetEntries(
        item: Any,
        isAllApps: Boolean = false,
        onNavigate: (View) -> Unit
    ): List<SheetRow> {
        val resolved = if (item is TagItemEntity) resolveItem(item) else item
        return when (resolved) {
            is App -> {
                val favs = tagItemDao.getItemsForTag(TAG.FAV).first()
                val isFav = favs.any { it.type == TagItemType.APP && it.packageName == resolved.componentName.packageName }
                val favText = if (isFav) "Remove from favorites" else "Add to favorites"
                
                buildList {
                    add(SheetRow(favText) { 
                        viewModelScope.launch { 
                            if (isFav) {
                                favs.find { it.type == TagItemType.APP && it.packageName == resolved.componentName.packageName }?.let { tagItemDao.delete(it) }
                            } else {
                                tagItemDao.insert(TagItemEntity(TAG.FAV, favs.size, TagItemType.APP, resolved.componentName.packageName))
                            }
                        } 
                    })
                    add(SheetRow("Edit Favorites") {
                        onNavigate(View.ManageTag(TAG.FAV, "Favorites"))
                    })
                    if (!isAllApps && item is TagItemEntity) {
                        add(SheetRow("Edit Tag") {
                            viewModelScope.launch {
                                val tagId = getOrCreatePopupTag(item)
                                onNavigate(View.ManageTag(tagId, item.labelOverride ?: resolved.label.toString()))
                            }
                        })
                    }
                    add(SheetRow("Open Settings") {
                        launcherApps.startAppDetailsActivity(resolved.componentName, user, null, null)
                    })
                    add(SheetRow("Uninstall") {
                        val context = getApplication<Application>()
                        context.startActivity(Intent(Intent.ACTION_DELETE).apply {
                            data = "package:${resolved.componentName.packageName}".toUri()
                        })
                    })
                }
            }

            is Shortcut -> {
                val favs = tagItemDao.getItemsForTag(TAG.FAV).first()
                val isFav = favs.any { it.type == TagItemType.SHORTCUT && it.packageName == resolved.`package` && it.shortcutId == resolved.id }
                val favText = if (isFav) "Remove from favorites" else "Add to favorites"
                
                buildList {
                    add(SheetRow(favText) { 
                        viewModelScope.launch { 
                            if (isFav) {
                                favs.find { it.type == TagItemType.SHORTCUT && it.packageName == resolved.`package` && it.shortcutId == resolved.id }?.let { tagItemDao.delete(it) }
                            } else {
                                tagItemDao.insert(TagItemEntity(TAG.FAV, favs.size, TagItemType.SHORTCUT, resolved.`package`, resolved.id, labelOverride = resolved.shortLabel?.toString()))
                            }
                        } 
                    })
                    add(SheetRow("Edit Favorites") {
                        onNavigate(View.ManageTag(TAG.FAV, "Favorites"))
                    })
                    if (!isAllApps && item is TagItemEntity) {
                        add(SheetRow("Edit Tag") {
                            viewModelScope.launch {
                                val tagId = getOrCreatePopupTag(item)
                                onNavigate(View.ManageTag(tagId, item.labelOverride ?: resolved.shortLabel.toString()))
                            }
                        })
                    }
                    add(SheetRow("Open Settings") {
                        launcherApps.startAppDetailsActivity(resolved.activity, user, null, null)
                    })
                }
            }

            is Tag -> {
                buildList {
                    // Tag is always a popup in this context
                    if (!isAllApps && item is TagItemEntity) {
                        add(SheetRow("Edit Tag") {
                            onNavigate(View.ManageTag(resolved.id, item.labelOverride ?: "Folder"))
                        })
                        add(SheetRow("Edit Favorites") {
                            onNavigate(View.ManageTag(TAG.FAV, "Favorites"))
                        })
                        add(SheetRow("Remove from Favorites") {
                            viewModelScope.launch { tagItemDao.delete(item) }
                        })
                        add(SheetRow("Delete Tag") {
                            viewModelScope.launch { deleteTag(resolved.id) }
                        })
                    }
                }
            }

            else -> emptyList()
        }
    }

    private fun appsToRows(list: List<LauncherActivityInfo>) = list.map {
        UiRow(it.label.toString(), it.getIcon(0).toBitmap().asImageBitmap(), it)
    }

    private fun shortcutsToRows(list: List<ShortcutInfo>) = list.map {
        UiRow(
            it.shortLabel.toString(),
            launcherApps.getShortcutIconDrawable(it, 0)?.toBitmap()?.asImageBitmap(),
            it
        )
    }
}