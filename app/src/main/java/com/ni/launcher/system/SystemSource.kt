package com.ni.launcher.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import com.ni.launcher.TAG
import com.ni.launcher.model.AppNode
import com.ni.launcher.model.Command
import com.ni.launcher.model.ItemKey
import com.ni.launcher.model.LauncherGraphStore
import com.ni.launcher.model.ShortcutNode
import com.ni.launcher.model.SystemData
import com.ni.launcher.model.UserData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private const val LOG_TAG = "SystemSource"
/** Debug-only startup/refresh timing tag: `adb logcat -s NiGraph`. */
private const val TIMING_TAG = "NiGraph"
const val SYSTEM_REFRESH_COALESCE_MS = 150L
private const val ICON_BATCH = 32
private const val LOADED_WAIT_MS = 500L
private const val E2E_HOLD_TAG = "NiE2EHoldShortcuts"
private const val E2E_HOLD_SHORTCUTS_MS = 30_000L

/**
 * Adapter from LauncherApps to the graph store (design doc §3, §8). All IPC, label loading and
 * icon decoding run on [io]; the LauncherApps callback runs on a private HandlerThread and only
 * signals work.
 *
 * A full refresh is a staged sequence; every stage dispatches a complete [SystemData] built on
 * the previously published one ([Command.SystemChanged], not persisted):
 *  1. apps + labels (icons only if already cached, no decoding)
 *  2. icons of apps shown in Favorites (direct apps + tag representatives)
 *  3. remaining app icons, batches of [ICON_BATCH]
 *  4. shortcuts (one all-package query; icons only if cached)
 *  5. shortcut icons: tag-referenced first, then favorites' packages, then the rest in batches
 * Unchanged nodes/maps keep their previous instances, and stages that change nothing are not
 * dispatched, so a warm refresh mostly publishes nothing.
 *
 * Concurrency: a new full refresh cancels the running sequence (cancelAndJoin) and restarts it.
 * Stages and per-package shortcut updates mutate [current] only under [mutex] and check for
 * cancellation right before dispatching, so a cancelled sequence never publishes a stale stage.
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
    private val debugTiming =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val iconCache: IconCache = appContext.resources.displayMetrics.let { dm ->
        IconCache(launcherApps, px = (ICON_DP * dm.density).roundToInt(), densityDpi = dm.densityDpi)
    }

    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private val shortcutSignal = Channel<Unit>(Channel.CONFLATED)
    private val dirtyPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val dirtyShortcutPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val mutex = Mutex()
    /** Last SystemData this source dispatched. Written only under [mutex]. */
    @Volatile private var current: SystemData = SystemData.EMPTY

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
        override fun onShortcutsChanged(packageName: String, shortcuts: List<ShortcutInfo>, user: UserHandle) {
            // Per-package partial update. The list is used only as a signal: the package is
            // re-queried so labels/flags are complete regardless of what the callback carries.
            dirtyShortcutPackages.add(packageName)
            shortcutSignal.trySend(Unit)
        }
    }

    /** Registers the callback (on a background looper) and starts the first staged sequence. */
    @Synchronized
    fun start() {
        if (job != null) return
        val t = HandlerThread("launcher-system").also { it.start() }
        thread = t
        launcherApps.registerCallback(callback, Handler(t.looper))
        job = scope.launch(io) {
            launch { shortcutLoop() }
            var sequence: Job? = null
            var first = true
            for (u in refresh) {
                if (!first) delay(coalesceMs) // coalesce callback bursts
                first = false
                sequence?.cancelAndJoin()
                sequence = launch { runSequence() }
            }
        }
        requestRefresh()
    }

    /** Unregisters the callback, stops the workers and the HandlerThread. */
    @Synchronized
    fun close() {
        launcherApps.unregisterCallback(callback)
        job?.cancel()
        job = null
        thread?.quitSafely()
        thread = null
    }

    /**
     * Requests a coalesced full refresh (restarts a running sequence). Call after an uninstall
     * request, since the package manager can lag behind the uninstall UI.
     */
    fun requestRefresh() {
        refresh.trySend(Unit)
    }

    private fun changed(pkg: String?) {
        if (pkg != null) dirtyPackages.add(pkg)
        requestRefresh()
    }

    // ---------------------------------------------------------------- staged full refresh

    private class AppEntry(val info: LauncherActivityInfo, val lastUpdateTime: Long)

    private suspend fun runSequence() {
        val t0 = SystemClock.elapsedRealtime()
        try {
            // Icons of changed packages may change without a new timestamp.
            dirtyPackages.toList().forEach {
                dirtyPackages.remove(it)
                iconCache.invalidatePackage(it)
            }

            // 1. apps + labels
            val entries = HashMap<String, AppEntry>()
            stage(1, "apps+labels", t0) { cur ->
                val list = queryActivities()
                val prev = cur.apps
                val apps = LinkedHashMap<String, AppNode>(list.size)
                val updateTimes = HashMap<String, Long>()
                for (info in list) {
                    val pkg = info.componentName.packageName
                    val component = info.componentName.flattenToString()
                    if (component in apps) continue
                    val entry = AppEntry(info, updateTimes.getOrPut(pkg) { lastUpdateTime(info) })
                    entries[component] = entry
                    val node = AppNode(
                        pkg = pkg,
                        component = component,
                        label = info.label?.toString().orEmpty(),
                        icon = iconCache.peekApp(iconCache.appKey(info, entry.lastUpdateTime)),
                    )
                    apps[component] = prev[component]?.takeIf { it == node } ?: node
                }
                cur.copy(apps = reuseMap(prev, apps))
            }

            // 2. favorites' app icons (wait briefly for Loaded without blocking a thread)
            val user = withTimeoutOrNull(LOADED_WAIT_MS) { store.graph.first { it.loaded } }?.user
            val favPkgs = user?.let { favoriteAppPackages(it) }.orEmpty()
            val favComponents = entries.filter { (_, e) -> e.info.componentName.packageName in favPkgs }.keys
            stage(2, "favorite icons (${favComponents.size})", t0) { cur -> withAppIcons(cur, entries, favComponents) }

            // 3. remaining app icons in batches
            val rest = entries.keys.filter { it !in favComponents && current.apps[it]?.icon == null }
            rest.chunked(ICON_BATCH).forEachIndexed { i, batch ->
                yield()
                stage(3, "app icons batch ${i + 1}/${(rest.size + ICON_BATCH - 1) / ICON_BATCH}", t0) { cur ->
                    withAppIcons(cur, entries, batch)
                }
            }

            // 4. shortcuts without decoding
            e2eHoldShortcuts()
            var infos: List<ShortcutInfo> = emptyList()
            stage(4, "shortcuts", t0) { cur ->
                infos = queryShortcuts(null) ?: return@stage cur
                cur.copy(shortcuts = reuseShortcutMap(cur.shortcuts, infos.groupBy { it.`package` }, decode = false))
            }

            // 5. shortcut icons, prioritized
            val latestUser = store.graph.value.user.takeIf { store.graph.value.loaded } ?: user
            val ordered = prioritizeShortcuts(infos, latestUser, favPkgs)
            ordered.chunked(ICON_BATCH).forEachIndexed { i, batch ->
                yield()
                stage(5, "shortcut icons batch ${i + 1}", t0) { cur -> withShortcutIcons(cur, batch) }
            }
            timing("sequence done", t0)
        } catch (e: CancellationException) {
            timing("sequence cancelled (restart)", t0)
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "system refresh failed", e)
        }
    }

    /**
     * Runs [build] on the latest published SystemData under the mutex and dispatches the result
     * if it changed. Cancellation is checked right before publishing.
     */
    private suspend fun stage(n: Int, what: String, t0: Long, build: suspend (SystemData) -> SystemData) {
        mutex.withLock {
            val next = build(current)
            currentCoroutineContext().ensureActive()
            val changed = next != current
            if (changed) {
                current = next
                store.dispatch(Command.SystemChanged(next))
            }
            timing("stage $n $what${if (changed) "" else " (unchanged)"}", t0)
        }
    }

    /** Decodes missing icons for [components] (keys of [SystemData.apps]). */
    private fun withAppIcons(
        cur: SystemData,
        entries: Map<String, AppEntry>,
        components: Collection<String>,
    ): SystemData {
        var apps: MutableMap<String, AppNode>? = null
        for (component in components) {
            val node = cur.apps[component] ?: continue
            if (node.icon != null) continue
            val e = entries[component] ?: continue
            val icon = iconCache.app(e.info, e.lastUpdateTime) ?: continue
            (apps ?: LinkedHashMap(cur.apps).also { apps = it })[component] = node.copy(icon = icon)
        }
        return apps?.let { cur.copy(apps = it) } ?: cur
    }

    private fun withShortcutIcons(cur: SystemData, batch: List<ShortcutInfo>): SystemData {
        val byPkg = batch.groupBy { it.`package` }
        var shortcuts: MutableMap<String, List<ShortcutNode>>? = null
        for ((pkg, infos) in byPkg) {
            val nodes = cur.shortcuts[pkg] ?: continue
            val icons = infos.associate { it.id to iconCache.shortcut(it) }
            val updated = nodes.map { n ->
                val icon = icons[n.id]
                if (icon != null && n.icon == null) n.copy(icon = icon) else n
            }
            if (updated != nodes) {
                (shortcuts ?: LinkedHashMap(cur.shortcuts).also { shortcuts = it })[pkg] = updated
            }
        }
        return shortcuts?.let { cur.copy(shortcuts = it) } ?: cur
    }

    /** Packages whose app icon is visible in Favorites: direct apps + each tag's representative. */
    private fun favoriteAppPackages(user: UserData): Set<String> {
        val fav = user.tags[TAG.FAV] ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (edge in fav.children) {
            var key: ItemKey? = edge.key
            val visited = HashSet<Long>()
            while (key is ItemKey.Tag && visited.add(key.id)) {
                key = user.tags[key.id]?.children?.firstOrNull()?.key
            }
            when (key) {
                is ItemKey.App -> out += key.pkg
                is ItemKey.Shortcut -> out += key.pkg
                else -> Unit
            }
        }
        return out
    }

    /** Tag-referenced shortcuts first, then favorites' packages, then the rest (stable). */
    private fun prioritizeShortcuts(
        infos: List<ShortcutInfo>,
        user: UserData?,
        favPkgs: Set<String>,
    ): List<ShortcutInfo> {
        val referenced = user?.tags?.values.orEmpty()
            .flatMap { t -> t.children.mapNotNull { it.key as? ItemKey.Shortcut } }
            .toHashSet()
        return infos.sortedBy { si ->
            when {
                ItemKey.Shortcut(si.`package`, si.id) in referenced -> 0
                si.`package` in favPkgs -> 1
                else -> 2
            }
        }
    }

    // ---------------------------------------------------------------- per-package shortcuts

    private suspend fun shortcutLoop() {
        for (u in shortcutSignal) {
            val pkgs = dirtyShortcutPackages.toList()
            dirtyShortcutPackages.removeAll(pkgs.toSet())
            for (pkg in pkgs) {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    mutex.withLock {
                        val infos = queryShortcuts(pkg) ?: return@withLock
                        val cur = current
                        val nodes = buildShortcutNodes(cur.shortcuts[pkg], infos, decode = true)
                        val map = LinkedHashMap(cur.shortcuts)
                        if (nodes.isEmpty()) map.remove(pkg) else map[pkg] = nodes
                        val next = cur.copy(shortcuts = map)
                        if (next != cur) {
                            current = next
                            store.dispatch(Command.SystemChanged(next))
                        }
                    }
                    timing("shortcuts $pkg updated", t0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "shortcut update for $pkg failed", e)
                }
            }
        }
    }

    // ---------------------------------------------------------------- queries and builders

    private fun queryActivities(): List<LauncherActivityInfo> = try {
        launcherApps.getActivityList(null, user).orEmpty()
    } catch (e: SecurityException) {
        Log.w(LOG_TAG, "getActivityList denied", e)
        emptyList()
    }

    /** `<queries>` MAIN/LAUNCHER in the manifest makes launchable packages visible here. */
    private fun lastUpdateTime(info: LauncherActivityInfo): Long = try {
        packageManager.getPackageInfo(info.componentName.packageName, 0).lastUpdateTime
    } catch (_: PackageManager.NameNotFoundException) {
        info.firstInstallTime
    }

    /** All packages if [pkg] is null. Returns null when shortcuts are unavailable to us. */
    private fun queryShortcuts(pkg: String?): List<ShortcutInfo>? = try {
        if (!launcherApps.hasShortcutHostPermission()) {
            null
        } else {
            launcherApps.getShortcuts(
                ShortcutQuery().apply {
                    if (pkg != null) setPackage(pkg)
                    setQueryFlags(
                        ShortcutQuery.FLAG_MATCH_MANIFEST or
                            ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            ShortcutQuery.FLAG_MATCH_PINNED
                    )
                },
                user,
            ).orEmpty()
        }
    } catch (e: SecurityException) { // not the default launcher
        Log.w(LOG_TAG, "getShortcuts denied", e)
        null
    } catch (e: IllegalStateException) { // user locked / not running
        Log.w(LOG_TAG, "getShortcuts unavailable", e)
        null
    }

    private fun reuseShortcutMap(
        prev: Map<String, List<ShortcutNode>>,
        byPkg: Map<String, List<ShortcutInfo>>,
        decode: Boolean,
    ): Map<String, List<ShortcutNode>> =
        reuseMap(prev, byPkg.mapValues { (pkg, infos) -> buildShortcutNodes(prev[pkg], infos, decode) })

    private fun buildShortcutNodes(
        prev: List<ShortcutNode>?,
        infos: List<ShortcutInfo>,
        decode: Boolean,
    ): List<ShortcutNode> {
        val prevById = prev?.associateBy { it.id }.orEmpty()
        val nodes = infos
            .map { si ->
                val node = ShortcutNode(
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
                    icon = if (decode) iconCache.shortcut(si) else iconCache.peekShortcut(si),
                )
                prevById[si.id]?.takeIf { it == node } ?: node
            }
            .sortedWith(compareBy<ShortcutNode> { !it.manifest && !it.dynamic }.thenBy { it.rank })
        return if (prev != null && prev == nodes) prev else nodes
    }

    private fun <K, V> reuseMap(prev: Map<K, V>, next: Map<K, V>): Map<K, V> =
        if (prev == next) prev else next

    /**
     * Debuggable builds only: `adb shell setprop log.tag.NiE2EHoldShortcuts DEBUG` delays stage 4
     * by [E2E_HOLD_SHORTCUTS_MS], so the e2e test can reliably act while apps are known but
     * shortcuts are not (the window is often too short on a warm start).
     */
    private suspend fun e2eHoldShortcuts() {
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && Log.isLoggable(E2E_HOLD_TAG, Log.DEBUG)) {
            Log.w(E2E_HOLD_TAG, "holding shortcut discovery for ${E2E_HOLD_SHORTCUTS_MS} ms")
            delay(E2E_HOLD_SHORTCUTS_MS)
        }
    }

    private fun timing(what: String, t0: Long) {
        if (!debugTiming) return
        val now = SystemClock.elapsedRealtime()
        Log.d(TIMING_TAG, "$what: +${now - t0} ms (process +${now - Process.getStartElapsedRealtime()} ms)")
    }

    private companion object {
        /** RowIcon size (MainActivity RowIcon). */
        const val ICON_DP = 40f
    }
}
