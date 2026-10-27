package com.ni.launcher.system

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.UserHandle
import android.util.Log
import com.ni.launcher.model.AppNode
import com.ni.launcher.model.Command
import com.ni.launcher.model.LauncherGraphStore
import com.ni.launcher.model.ShortcutNode
import com.ni.launcher.model.SystemData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private const val LOG_TAG = "SystemSource"
const val SYSTEM_REFRESH_COALESCE_MS = 150L

/**
 * Adapter from LauncherApps to the graph store (design doc §3, §8). All IPC, label loading and
 * icon decoding happen on [io]; the LauncherApps callback runs on a private HandlerThread and
 * only signals a refresh. Every refresh builds a complete [SystemData] and dispatches
 * [Command.SystemChanged] (not persisted; the reducer drops it if nothing changed).
 *
 * Callback bursts are coalesced ([SYSTEM_REFRESH_COALESCE_MS]); a refresh re-queries everything
 * (one getActivityList + one getShortcuts for all packages), icons come from [IconCache] so only
 * new/changed ones are decoded.
 */
class SystemSource(
    context: Context,
    private val store: LauncherGraphStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val coalesceMs: Long = SYSTEM_REFRESH_COALESCE_MS,
) {
    private val appContext = context.applicationContext
    private val launcherApps: LauncherApps = appContext.getSystemService(LauncherApps::class.java)
    private val packageManager: PackageManager = appContext.packageManager
    private val user: UserHandle = Process.myUserHandle()

    val iconCache: IconCache = appContext.resources.displayMetrics.let { dm ->
        IconCache(launcherApps, px = (ICON_DP * dm.density).roundToInt(), densityDpi = dm.densityDpi)
    }

    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private val dirtyPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var thread: HandlerThread? = null
    private var job: Job? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)
        override fun onShortcutsChanged(packageName: String, shortcuts: List<ShortcutInfo>, user: UserHandle) =
            requestRefresh()
    }

    /** Registers the callback (on a background looper) and publishes the first snapshot. */
    @Synchronized
    fun start() {
        if (job != null) return
        val t = HandlerThread("launcher-system").also { it.start() }
        thread = t
        launcherApps.registerCallback(callback, Handler(t.looper))
        job = scope.launch(io) {
            var first = true
            for (u in refresh) {
                if (!first) delay(coalesceMs) // coalesce callback bursts
                first = false
                try {
                    publish()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "system refresh failed", e)
                }
            }
        }
        requestRefresh()
    }

    /** Unregisters the callback, stops the worker and the HandlerThread. */
    @Synchronized
    fun close() {
        launcherApps.unregisterCallback(callback)
        job?.cancel()
        job = null
        thread?.quitSafely()
        thread = null
    }

    /** Triggers a coalesced refresh, e.g. after an uninstall request (package manager may lag). */
    fun requestRefresh() {
        refresh.trySend(Unit)
    }

    private fun changed(pkg: String?) {
        if (pkg != null) dirtyPackages.add(pkg)
        requestRefresh()
    }

    private fun publish() {
        // Icons of changed packages may change without a new timestamp (e.g. component toggles).
        dirtyPackages.toList().forEach {
            dirtyPackages.remove(it)
            iconCache.invalidatePackage(it)
        }
        val apps = loadApps()
        val shortcuts = loadShortcuts()
        store.dispatch(Command.SystemChanged(SystemData(apps, shortcuts)))
    }

    private fun loadApps(): Map<String, AppNode> {
        val list: List<LauncherActivityInfo> = try {
            launcherApps.getActivityList(null, user).orEmpty()
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "getActivityList denied", e)
            emptyList()
        }
        val out = LinkedHashMap<String, AppNode>(list.size)
        for (info in list) {
            val pkg = info.componentName.packageName
            if (pkg in out) continue // keyed by package: first activity wins (as AppsVM.apps.find)
            out[pkg] = AppNode(
                pkg = pkg,
                component = info.componentName.flattenToString(),
                label = info.label?.toString().orEmpty(),
                icon = iconCache.app(info, lastUpdateTime(info)),
            )
        }
        return out
    }

    private fun lastUpdateTime(info: LauncherActivityInfo): Long = try {
        packageManager.getPackageInfo(info.componentName.packageName, 0).lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        info.firstInstallTime
    }

    private fun loadShortcuts(): Map<String, List<ShortcutNode>> {
        val infos: List<ShortcutInfo> = try {
            if (!launcherApps.hasShortcutHostPermission()) return emptyMap()
            launcherApps.getShortcuts(
                ShortcutQuery().setQueryFlags(
                    ShortcutQuery.FLAG_MATCH_MANIFEST or
                        ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        ShortcutQuery.FLAG_MATCH_PINNED
                ),
                user,
            ).orEmpty()
        } catch (e: SecurityException) { // not the default launcher
            Log.w(LOG_TAG, "getShortcuts denied", e)
            return emptyMap()
        } catch (e: IllegalStateException) { // user locked / not running
            Log.w(LOG_TAG, "getShortcuts unavailable", e)
            return emptyMap()
        }
        return infos
            .map { si ->
                ShortcutNode(
                    pkg = si.`package`,
                    id = si.id,
                    label = (si.shortLabel ?: si.longLabel)?.toString().orEmpty(),
                    enabled = si.isEnabled,
                    disabledMsg = si.disabledMessage?.toString(),
                    manifest = si.isDeclaredInManifest,
                    dynamic = si.isDynamic,
                    pinned = si.isPinned,
                    rank = si.rank,
                    changedAt = si.lastChangedTimestamp,
                    activity = si.activity?.flattenToString(),
                    icon = iconCache.shortcut(si),
                )
            }
            .groupBy { it.pkg }
            .mapValues { (_, l) -> l.sortedWith(compareBy<ShortcutNode> { !it.manifest && !it.dynamic }.thenBy { it.rank }) }
    }

    private companion object {
        /** RowIcon size (MainActivity RowIcon). */
        const val ICON_DP = 40f
    }
}
