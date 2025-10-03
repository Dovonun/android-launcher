package com.example.launcher

import android.app.Application
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class AppListData(
    val items: List<ListItem>,
    val letterToIndex: Map<Char, Int>,
)

class AppsVM(application: Application) : AndroidViewModel(application) {
    private val launcherApps: LauncherApps = application.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = android.os.Process.myUserHandle()
    private val sharedPreferences =
        application.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
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

    // PWA stuff
    private val _pwaList = MutableStateFlow(loadPwaListFromPrefs())
    val pwaList: StateFlow<List<App.Pwa>> = _pwaList.asStateFlow()
    private fun loadPwaListFromPrefs(): List<App.Pwa> {
        val prefs = sharedPreferences.getString("pwas", "[]") ?: error("Can't access system prefs!")
        val array = JSONArray(prefs)
        return List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            App.Pwa(
                name = obj.getString("label"),
                id = obj.getString("id"),
                packageName = obj.getString("packageName"),
                icon = null,
            )
        }
    }

    fun savePwa(id: String, packageName: String, label: String) {
        val list = _pwaList.value.toMutableList()
        list.add(App.Pwa(label, packageName, id, null))
        _pwaList.value = list
        sharedPreferences.edit {
            putString("pwas", JSONArray(list.map { pwa ->
                JSONObject().apply {
                    put("id", pwa.id)
                    put("packageName", pwa.packageName)
                    put("label", pwa.name)
                }
            }).toString())
        }
        refreshApps()
    }


    // apps and favs
    private val _installedApps = MutableStateFlow<List<App>>(emptyList())
    private val _favorites = MutableStateFlow(
        sharedPreferences.getStringSet("favorites", emptySet())?.toSet()
            ?: error("Can't access system prefs!")
    )
    val appListData: StateFlow<AppListData> = _installedApps.map { apps ->
        val items = mutableListOf<ListItem>()
        val letterToIndex = mutableMapOf<Char, Int>()
        var lastLetter: Char? = null

        apps.forEach { app ->
            if (app is App.Pwa) Log.d("AppsVM", "pwa: $app")
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

    fun isFavorite(packageName: String) = _favorites.value.contains(packageName)
    fun toggleFavorite(packageName: String) {
        _favorites.value =
            if (isFavorite(packageName)) _favorites.value - packageName else _favorites.value + packageName
        sharedPreferences.edit { putStringSet("favorites", _favorites.value) }
    }

    private fun refreshApps() {
        val nativeApps = launcherApps.getActivityList(null, user).map { app ->
            App.Native(
                name = app.label.toString(),
                packageName = app.componentName.packageName,
                icon = app.getIcon(0).toBitmap().asImageBitmap()
            )
        }
        Log.d("AppsVM", "nativeApps: $nativeApps")
        Log.d("AppsVM", "pwaList: ${pwaList.value}")
        _installedApps.value = (nativeApps + pwaList.value).sortedBy { it.name.lowercase() }
    }

    fun launch(context: Context, app: App) {
        when (app) {
            is App.Native -> context.startActivity(
                context.packageManager.getLaunchIntentForPackage(
                    app.packageName
                )
            )

            is App.Pwa -> launcherApps.startShortcut(app.packageName, app.id, null, null, user)
        }
    }

    // shortcut popup
    private val _selectedApp = MutableStateFlow<App?>(null)
    val selectedApp: StateFlow<App?> = _selectedApp.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _lastShortcuts: StateFlow<List<ShortcutInfo>> = _selectedApp.mapLatest { app ->
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
        selectApp(null)
    }

    init {
        launcherApps.registerCallback(callback)
        refreshApps()
    }

    override fun onCleared() {
        super.onCleared()
        launcherApps.unregisterCallback(callback)
    }
}