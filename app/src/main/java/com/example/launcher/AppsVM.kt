package com.example.launcher

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import kotlin.Any
import kotlin.Unit

data class UiRow(val label: String, val icon: Drawable, val item: Any)
typealias App = LauncherActivityInfo
typealias Shortcut = ShortcutInfo

fun createCallback(cb: () -> Unit) = object : LauncherApps.Callback() {
    override fun onPackageAdded(packageName: String, user: UserHandle) = cb()
    override fun onPackageRemoved(packageName: String?, user: UserHandle?) = cb()
    override fun onPackageChanged(packageName: String?, user: UserHandle?) = cb()
    override fun onPackagesAvailable(
        packageNames: Array<String>, user: UserHandle, replacing: Boolean
    ) = cb()

    override fun onPackagesUnavailable(
        packageNames: Array<String>, user: UserHandle, replacing: Boolean
    ) = cb()
}


class AppsVM(application: Application) : AndroidViewModel(application) {
    private val launcherApps: LauncherApps = application.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = android.os.Process.myUserHandle()

    init {
        launcherApps.registerCallback(createCallback(::refreshApps))
        refreshApps()
    }

    private val _nativeApps = MutableStateFlow<List<LauncherActivityInfo>>(emptyList())
    private fun refreshApps() = _nativeApps.update { launcherApps.getActivityList(null, user) }


    // TODO: implement
    private val _allAppsList: StateFlow<List<Any>> = combine(_nativeApps) { natives ->
        (natives + pwas).sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val favoriteList = {
        val favoriteAppsTags: Any = Unit // TODO: get favorites from db
        val favoriteShortcuts: Any = Unit // TODO: get favorites from db

        val favoritesApps =
            _nativeApps.value.filter { favoriteAppsTags.contains(it.componentName) } // TODO: get the LauncherActivityInfo for each app with favorite tag
        val favoritesShortcuts<ShortcutInfo> = favoriteShortcuts.value.map { // TODO: get a list of ShortcutInfo for each shortcut with favorite tag
                launcherApps.getShortcuts(ShortcutQuery().apply {
                    setPackage(it.`package`)
                    setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
                }, user)
            }

        favoritesApps.map {
            UiRow( it.label.toString(), it.activityInfo.icon, it )
        } + favoritesShortcuts.map { UiRow(it.shortLable, it.icon, it) } // Map both to UiRow and combine them
    }

//    val favoriteApps: StateFlow<List<Launchable>> = combine(_installedApps, _favorites) { apps, favs ->
//        val appMap = apps.associateBy { it.packageName }
//        favs.mapNotNull { appMap[it] }
//    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

}