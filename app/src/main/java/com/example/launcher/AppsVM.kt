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
import com.example.launcher.data.TaggedAppEntity
import com.example.launcher.data.TaggedShortcutEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Tag(val id: Long)
data class UiRow(val label: String, val icon: ImageBitmap, val item: Any)
data class SheetRow(val label: String, val onTap: () -> Unit)
typealias App = LauncherActivityInfo
typealias Shortcut = ShortcutInfo

object TAG {
    const val FAV: Long = 1
    const val PWA: Long = 2
}

suspend fun ensureSystemTags(tagDao: TagDao) {
    val existing = tagDao.getAll()
    val tags =
        listOf(TagEntity(id = TAG.FAV, name = "Favorite"), TagEntity(id = TAG.PWA, name = "PWA"))
    tags.forEach { tag ->
        if (existing.find { it.id == tag.id && it.name == tag.name } == null) tagDao.insert(tag)
    }
}

data class IconCacheKey(val pkg: String, val id: String?)

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
    private val taggedAppDao = db.taggedAppDao()
    private val taggedShortcutDao = db.taggedShortcutDao()
    private val launcherApps: LauncherApps = application.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = android.os.Process.myUserHandle()
    private val apps = MutableStateFlow<List<LauncherActivityInfo>>(emptyList())

    //    private val iconCache = mutableMapOf<IconCacheKey, Drawable>()
    private val usedShortcuts = MutableStateFlow(emptyList<String>())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cachedShortcuts = combine(
        usedShortcuts, taggedShortcutDao.getDistinctPackages()
    ) { used, tagged -> (used + tagged).distinct() }.mapLatest { pkgs ->
        coroutineScope {
            pkgs.associateWith { pkg ->
                async {
                    launcherApps.getShortcuts(
                        ShortcutQuery().apply { setPackage(pkg) }, user
                    ).orEmpty()
                }
            }.mapValues { it.value.await() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun refreshApps() = apps.update { launcherApps.getActivityList(null, user) }
    private fun cleanup(pkg: String) = db.taggedAppDao() // TODO: delete all tags for this app

    init {
        viewModelScope.launch(Dispatchers.IO) { ensureSystemTags(tagDao) }
        launcherApps.registerCallback(createCallback(::refreshApps, ::cleanup))
        refreshApps()
    }

//    private fun deleteFromIconCache(pkg: String) = iconCache.keys.removeAll { it.pkg != pkg }
    // Maybe you don't need to treat IconCache and Db as separate things?
    // One cleanup interact with the system function to rule them all

    // TODO: popup list
    val uiAllGrouped = combine(
        apps, cachedShortcuts, taggedShortcutDao.getShortcutsForTag(TAG.PWA)
    ) { apps, shortcuts, pwas ->
        val uiApps = appsToRows(apps)
        val uiPwas = shortcutsToRows(pwas.mapNotNull { pwa ->
            shortcuts[pwa.packageName]?.firstOrNull { it.id == pwa.shortcutId }
        })
        (uiApps + uiPwas).sortedBy { it.label.lowercase() }
            .groupBy { it.label.first().uppercaseChar() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyMap<Char, List<UiRow>>())

    fun uiList(tag: Long): StateFlow<List<UiRow>> = combine(
        apps, cachedShortcuts,
        taggedAppDao.getPackagesForTag(tag), taggedShortcutDao.getShortcutsForTag(tag),
    ) { apps, shortcuts, appTags, shortcutTags ->
        val taggedApps = appsToRows(apps.filter { it.componentName.packageName in appTags })
        val taggedShortcuts = shortcutsToRows(shortcutTags.mapNotNull { shortcut ->
            shortcuts[shortcut.packageName]?.firstOrNull { it.id == shortcut.shortcutId }
        })
        taggedApps + taggedShortcuts
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyList<UiRow>())

    fun launch(item: Any) = when (val i = item) {
        is App -> launcherApps.startMainActivity(i.componentName, user, null, null)
        is Shortcut -> launcherApps.startShortcut(i.`package`, i.id, null, null, user)
        else -> error("Unreachable")
    }

    // shortcut popup
    // it seems like the pattern does not work here.
    // the lists you need are dynamic in two ways.
    // Based on the type(app or list)
    // after this is done it is dynamic based on the existing flows and the tag of the list
    fun popupEntires(item: Any): StateFlow<List<UiRow>> = when (item) {
        is App -> cachedShortcuts.map { shortcuts ->
            shortcutsToRows(shortcuts[item.componentName.packageName] ?: emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyList())

        is Tag -> uiList(item.id)
        else -> error("Unreachable")
    }
//    private val _selectedLaunchable = MutableStateFlow<Launchable?>(null)
//    val selectedLaunchable: StateFlow<Launchable?> = _selectedLaunchable.asStateFlow()

//    @OptIn(ExperimentalCoroutinesApi::class)
//    private val _lastShortcuts: StateFlow<List<ShortcutInfo>> =
//        _selectedLaunchable.mapLatest { app ->
//            if (app == null) emptyList() else getShortcuts(app.packageName)
//        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
//
//    val shortcutUiItems: StateFlow<List<UiShortcut>> = _lastShortcuts.map { list ->
//        list.map { shortcut ->
//            UiShortcut(
//                label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.`package`) as String,
//                icon = launcherApps.getShortcutIconDrawable(shortcut, 0).toBitmap().asImageBitmap()
//            )
//        }
//    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

//    fun selectApp(launchable: Launchable?) {
//        _selectedLaunchable.value = launchable
//    }

//    fun getShortcuts(packageName: String) = launcherApps.getShortcuts(ShortcutQuery().apply {
//        setPackage(packageName)
//        setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
//    }, user) ?: emptyList()

    // TODO: How do you get the shortcuts of the app to the popup?
    // Ideally in the future you want to show custom tags :)
    fun getContextEntries(item: Any): StateFlow<List<SheetRow>> = when (item) {
        is App -> taggedAppDao.getPackagesForTag(TAG.FAV).map { favs ->
            val dbTag = TaggedAppEntity(item.componentName.packageName, TAG.FAV)
            val isFav = dbTag.packageName in favs
            val favText = if (isFav) "Remove from favorites" else "Add to favorites"
            val dbAction = if (isFav) taggedAppDao::delete else taggedAppDao::insert
            buildList {
                add(
                    SheetRow(favText) { viewModelScope.launch { dbAction(dbTag) } })
                add(SheetRow("Open Settings") {
                    launcherApps.startAppDetailsActivity(item.componentName, user, null, null)
                })
                add(SheetRow("Uninstall") {
                    val context = getApplication<Application>()
                    context.startActivity(Intent(Intent.ACTION_DELETE).apply {
                        data = "package:${item.componentName.packageName}".toUri()
                    })
                })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        is Shortcut -> taggedShortcutDao.getShortcutsForTag(TAG.FAV).map { favs ->
            val itemTag = TaggedShortcutEntity(item.`package`, item.id, TAG.FAV)
            val isFav = favs.any { it.packageName == item.`package` && it.shortcutId == item.id }
            val favText = if (isFav) "Remove from favorites" else "Add to favorites"
            val dbAction = if (isFav) taggedShortcutDao::delete else taggedShortcutDao::insert
            buildList {
                add(SheetRow(favText) { viewModelScope.launch { dbAction(itemTag) } })
                add(SheetRow("Open Settings") {
                    launcherApps.startAppDetailsActivity(item.activity, user, null, null)
                })
                // TODO: add remove for PWAs
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        else -> error("unreachable")
    }

    private fun appsToRows(list: List<LauncherActivityInfo>) = list.map {
        UiRow(it.label.toString(), it.getIcon(0).toBitmap().asImageBitmap(), it)
    }

    private fun shortcutsToRows(list: List<ShortcutInfo>) = list.map {
        UiRow(
            it.shortLabel.toString(),
            launcherApps.getShortcutIconDrawable(it, 0).toBitmap().asImageBitmap(),
            it
        )
    }
}