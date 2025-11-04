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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    listOf(
        TagEntity(TAG.FAV, "Favorite"), TagEntity(TAG.PWA, "PWA")
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
    private val taggedAppDao = db.taggedAppDao()
    private val taggedShortcutDao = db.taggedShortcutDao()
    private val launcherApps: LauncherApps = application.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = android.os.Process.myUserHandle()
    private val apps = MutableStateFlow<List<LauncherActivityInfo>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    // THOUGHT: Could I cache all shortcuts?
    private val cachedShortcuts = combine(
        taggedAppDao.getPackagesForTag(TAG.FAV), taggedShortcutDao.getDistinctPackages()
    ) { favs, tagged -> (favs + tagged).distinct() }.mapLatest { pkgs ->
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
    private fun cleanup(pkg: String) = db.taggedAppDao()
    // TODO: delete all tags for this app

    init {
        viewModelScope.launch(Dispatchers.IO) { ensureSystemTags(tagDao) }
        launcherApps.registerCallback(createCallback(::refreshApps, ::cleanup))
        refreshApps()
    }

    val uiAllGrouped = combine(
        apps, cachedShortcuts, taggedShortcutDao.getShortcutsForTag(TAG.PWA)
    ) { apps, shortcuts, pwas ->
        val uiApps = appsToRows(apps)
        val uiPwas = shortcutsToRows(pwas.mapNotNull { pwa ->
            shortcuts[pwa.packageName]?.firstOrNull { it.id == pwa.shortcutId }
        })
        (uiApps + uiPwas).sortedBy { it.label.lowercase() }
            .groupBy { it.label.first().uppercaseChar() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(500), emptyMap())

    val favorites = uiList(TAG.FAV).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    fun uiList(tag: Long): Flow<List<UiRow>> = combine(
        apps, cachedShortcuts,
        taggedAppDao.getPackagesForTag(tag), taggedShortcutDao.getShortcutsForTag(tag),
    ) { apps, shortcuts, appTags, shortcutTags ->
        val taggedApps = appsToRows(apps.filter { it.componentName.packageName in appTags })
        val taggedShortcuts = shortcutsToRows(shortcutTags.mapNotNull { shortcut ->
            shortcuts[shortcut.packageName]?.firstOrNull { it.id == shortcut.shortcutId }
        })
        taggedApps + taggedShortcuts
    }

    fun launch(item: Any) = when (val i = item) {
        is App -> launcherApps.startMainActivity(i.componentName, user, null, null)
        is Shortcut -> launcherApps.startShortcut(i.`package`, i.id, null, null, user)
        else -> error("Unreachable")
    }

    suspend fun popupEntries(item: Any): List<UiRow> = when (item) {
        is Tag -> uiList(item.id).first()
        is App -> {
            val pkg = item.componentName.packageName
            val cache = cachedShortcuts.value
            if (pkg in cache) shortcutsToRows(cache[pkg] ?: emptyList()) else shortcutsToRows(
                launcherApps.getShortcuts(
                    ShortcutQuery().apply {
                        setPackage(pkg)
                        setQueryFlags(
                            ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST
                        )
                    }, user
                ).orEmpty()
            )
        }

        else -> error("Unreachable")
    }

    suspend fun sheetEntries(item: Any): List<SheetRow> = when (item) {
        is App -> {
            val favPkgs = taggedAppDao.getPackagesForTag(TAG.FAV).first()
            val dbTag = TaggedAppEntity(item.componentName.packageName, TAG.FAV)
            val isFav = dbTag.packageName in favPkgs
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
        }

        is Shortcut -> {
            val favs = taggedShortcutDao.getShortcutsForTag(TAG.FAV).first()
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
        }

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