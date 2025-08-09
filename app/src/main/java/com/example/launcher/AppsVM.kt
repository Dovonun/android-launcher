package com.example.launcher

import android.app.Application
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import androidx.core.content.edit
import kotlinx.coroutines.flow.combine

data class AppListData(
    val items: List<ListItem>,
    val letterToIndex: Map<Char, Int>,
)

class AppsVM(application: Application) : AndroidViewModel(application) {
    private val launcherApps: LauncherApps = application.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = android.os.Process.myUserHandle()

    private val sharedPreferences = application.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    private val _favorites = MutableStateFlow<Set<String>>(
        sharedPreferences.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    )
    private val _installedApps = MutableStateFlow<List<App>>(emptyList())
    private val _selectedApp = MutableStateFlow<App?>(null)
    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = refreshApps()
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = refreshApps()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = refreshApps()
        override fun onPackagesAvailable(
            packageNames: Array<out String?>?, user: UserHandle, replacing: Boolean
        ) {
            refreshApps()
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String?>?, user: UserHandle?, replacing: Boolean
        ) {
            refreshApps()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _lastShortcuts: StateFlow<List<ShortcutInfo>> = _selectedApp.mapLatest { app ->
        if (app == null) emptyList() else getShortcuts(app.packageName)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedApp: StateFlow<App?> = _selectedApp.asStateFlow()
    val shortcutUiItems: StateFlow<List<UiShortcut>> = _lastShortcuts.map { list ->
        list.map { shortcut ->
            UiShortcut(
                label = (shortcut.shortLabel ?: shortcut.longLabel ?: shortcut.`package`) as String,
                icon = launcherApps.getShortcutIconDrawable(shortcut, 0)?.toBitmap()?.asImageBitmap() ?: fallbackIcon
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appListData: StateFlow<AppListData> = _installedApps.map { apps ->
        val items = mutableListOf<ListItem>()
        val letterToIndex = mutableMapOf<Char, Int>()
        var lastLetter: Char? = null

        apps.forEach { app ->
            val firstChar = app.name.first().uppercaseChar()
            if (firstChar != lastLetter) {
                letterToIndex[firstChar] = items.size
                items.add(ListItem.Header(firstChar))
                lastLetter = firstChar
            }
            items.add(ListItem.AppEntry(app))
        }
        AppListData(items, letterToIndex)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppListData(emptyList(), emptyMap()))

    val favoriteApps: StateFlow<List<App>> = combine(_installedApps, _favorites) { apps, favs ->
        val appMap = apps.associateBy { it.packageName }
        favs.mapNotNull { appMap[it] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        launcherApps.registerCallback(callback)
        refreshApps()
    }

    override fun onCleared() {
        super.onCleared()
        launcherApps.unregisterCallback(callback)
    }

    private fun refreshApps() {
        _installedApps.value = launcherApps.getActivityList(null, user).map { app ->
            App(
                name = app.label.toString(), packageName = app.componentName.packageName, icon = app.getIcon(0).toBitmap().asImageBitmap()
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun selectApp(app: App?) {
        _selectedApp.value = app
    }

    fun getShortcuts(packageName: String) = launcherApps.getShortcuts(ShortcutQuery().apply {
        setPackage(packageName)
        setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
    }, user) ?: emptyList()

    fun launchShortcut(index: Int) {
        val shortcut = _lastShortcuts.value.getOrNull(index) ?: return
        launcherApps.startShortcut(shortcut.`package`, shortcut.id, null, null, user)
    }

    fun isFavorite(packageName: String) = _favorites.value.contains(packageName)

    fun toggleFavorite(packageName: String) {
        val newFav = if (isFavorite(packageName)) _favorites.value - packageName else _favorites.value + packageName
        _favorites.value = newFav
        sharedPreferences.edit { putStringSet("favorites", newFav) }
    }

//    fun getShortcutsForApp(packageName: String): List<UiShortcut> {
//        Log.d("LauncherViewModel", "getShortcutsForApp called with $packageName")
//        // TODO: remove hidden/disabled shortcuts | check if that is even needed
//        val query = ShortcutQuery().apply {
//            setPackage(packageName)
//            setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
//        }
//        return launcherApps.getShortcuts(query, user)?.mapNotNull { shortcut ->
//            val iconDrawable =
//                launcherApps.getShortcutIconDrawable(shortcut, 0) ?: return@mapNotNull null
//            UiShortcut(
//                label = shortcut.shortLabel?.toString() ?: shortcut.longLabel?.toString()
//                ?: shortcut.`package`,
//                icon = iconDrawable.toBitmap().asImageBitmap(),
//            )
//        } ?: emptyList()
//    }

}
