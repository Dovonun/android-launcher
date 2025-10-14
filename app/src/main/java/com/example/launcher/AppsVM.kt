package com.example.launcher

import android.app.Application
import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.Any
import kotlin.Unit
import kotlin.collections.map

data class UiRow(val label: String, val icon: Drawable, val item: Any)
typealias App = LauncherActivityInfo
typealias Shortcut = ShortcutInfo

val TAG_FAV: Long = 1
val TAG_PWA: Long = 2

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
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap<String, List<ShortcutInfo>>()
    )

    init {
        launcherApps.registerCallback(createCallback(::refreshApps, ::cleanup))
        refreshApps()
    }

    private fun refreshApps() = apps.update { launcherApps.getActivityList(null, user) }
    private fun cleanup(pkg: String) = db.taggedAppDao() // TODO: delete all tags for this app
//    private fun deleteFromIconCache(pkg: String) = iconCache.keys.removeAll { it.pkg != pkg }
    // Maybe you don't need to treat IconCache and Db as separate things?
    // One cleanup interact with the system function to rule them all


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

    fun hasTag(item: Any, tag: Int) = when (item) {
        is App -> favoriteList.value.contains(item.activityInfo.packageName)
        is Shortcut -> favoriteList.value.contains(item.`package`)
        else -> error("unreachable")
    }

//    fun toggleFavorite(packageName: String) {
//        _favorites.value =
//            if (isFavorite(packageName)) _favorites.value - packageName else _favorites.value + packageName
//        sharedPreferences.edit { putStringSet("favorites", _favorites.value) }
//    }

    fun launch(item: Any) = when (val i = item) {
        is App -> launcherApps.startMainActivity(i.componentName, user, null, null)
        is Shortcut -> launcherApps.startShortcut(i.`package`, i.id, null, null, user)
        else -> error("Unreachable")
    }

    // shortcut popup
    private val _selectedLaunchable = MutableStateFlow<Launchable?>(null)
    val selectedLaunchable: StateFlow<Launchable?> = _selectedLaunchable.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _lastShortcuts: StateFlow<List<ShortcutInfo>> =
        _selectedLaunchable.mapLatest { app ->
            if (app == null) emptyList() else getShortcuts(app.packageName)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val shortcutUiItems: StateFlow<List<UiShortcut>> = _lastShortcuts.map { list ->
        list.map { shortcut ->
            UiShortcut(
                label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.`package`) as String,
                icon = launcherApps.getShortcutIconDrawable(shortcut, 0).toBitmap().asImageBitmap()
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

//    fun selectApp(launchable: Launchable?) {
//        _selectedLaunchable.value = launchable
//    }

    fun getShortcuts(packageName: String) = launcherApps.getShortcuts(ShortcutQuery().apply {
        setPackage(packageName)
        setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
    }, user) ?: emptyList()

    private fun appsToRows(list: List<LauncherActivityInfo>) = list.map {
        UiRow(it.label.toString(), it.getIcon(0), it)
    }

    private fun shortcutsToRows(list: List<ShortcutInfo>) = list.map {
        UiRow(it.shortLabel.toString(), launcherApps.getShortcutIconDrawable(it, 0), it)
    }
}