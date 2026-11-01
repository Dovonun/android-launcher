package com.ni.launcher.model

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.ni.launcher.LauncherItem
import com.ni.launcher.PlaceholderKind
import com.ni.launcher.TAG
import com.ni.launcher.data.RoomGraphPersister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/*
 * The in-memory launcher graph: one immutable value ([Graph]) owned by [LauncherGraphStore], and
 * the UI rows derived from it. Data is grouped by lifetime: user tags change on every edit and
 * are persisted by RoomGraphPersister; [SystemData] (apps, shortcuts and indexes built from them)
 * changes only when SystemSource publishes, so user edits share it untouched.
 * Plain values only (ImageBitmap is the one exception), so `equals` is structural.
 */

/** Apps are keyed by package, like tag_items. (Subtypes stay: SystemSource matches on them.) */
sealed interface ItemKey {
    data class App(val pkg: String) : ItemKey
    data class Shortcut(val pkg: String, val id: String) : ItemKey
    data class Tag(val id: Long) : ItemKey
}

data class Edge(val key: ItemKey, val labelOverride: String? = null)

/** [children] order is the persisted itemOrder; keys are distinct. */
data class TagNode(val id: Long, val name: String, val children: List<Edge>)

/** [component] is `ComponentName.flattenToString()`. [icon] is the same instance per key (IconCache). */
data class AppNode(val pkg: String, val component: String, val label: String, val icon: ImageBitmap? = null)

data class ShortcutNode(
    val pkg: String,
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val disabledMsg: String? = null,
    val manifest: Boolean = false,
    val dynamic: Boolean = false,
    val pinned: Boolean = false,
    val rank: Int = 0,
    val changedAt: Long = 0L,
    val activity: String? = null,
    val icon: ImageBitmap? = null,
)

/** From SystemSource, never persisted. [apps] is keyed by component in getActivityList order; [shortcuts] by package. */
data class SystemData(val apps: Map<String, AppNode> = emptyMap(), val shortcuts: Map<String, List<ShortcutNode>> = emptyMap()) {
    /** First launcher activity per package (ItemKey.App resolves through it); built once per snapshot. */
    val appByPkg: Map<String, AppNode> by lazy { LinkedHashMap<String, AppNode>().also { m -> apps.values.forEach { m.putIfAbsent(it.pkg, it) } } }

    fun shortcut(k: ItemKey.Shortcut): ShortcutNode? = shortcuts[k.pkg]?.firstOrNull { it.id == k.id }

    companion object {
        val EMPTY = SystemData()
    }
}

const val FIRST_USER_TAG_ID = 3L // 1 and 2 are TAG.FAV and TAG.PINNED

/** One snapshot. [version] is first so StateFlow's equality check stops there. [nextTagId] is > every tag id ever used. */
data class Graph(
    val version: Long = 0L,
    val loaded: Boolean = false,
    val tags: Map<Long, TagNode> = emptyMap(),
    val nextTagId: Long = FIRST_USER_TAG_ID,
    val system: SystemData = SystemData.EMPTY,
) {
    /** SystemSource reads `graph.user.tags`. */
    val user: Graph get() = this

    companion object {
        val EMPTY = Graph()
    }
}

typealias UserData = Graph // SystemSource reads tags as `UserData`

/** SystemSource calls `store.dispatch(Command.SystemChanged(s))`; that's all that is left of Command. */
object Command {
    @Suppress("FunctionName")
    fun SystemChanged(s: SystemData): (Graph) -> Graph = { g -> if (g.system == s) g else g.copy(system = s) }
}

/**
 * App-scoped single source of truth. Every edit is a function queued on [inbox] and run one at a
 * time on a single model thread, so the snapshot needs no locks. Edits queued before Room has
 * loaded run, in order, right after the load. After an effective edit the new snapshot is
 * published first, then handed to the persister, which writes changed tags behind. Edits return
 * the same Graph when nothing changed; untouched TagNodes keep their identity, so the persister
 * and the projections compare with `===`.
 */
class LauncherGraphStore(private val scope: CoroutineScope, private val persister: RoomGraphPersister) {
    private val _graph = MutableStateFlow(Graph.EMPTY)
    val graph: StateFlow<Graph> = _graph

    private val inbox = Channel<(Graph) -> Graph>(Channel.UNLIMITED)
    private val nextId = AtomicLong(-1) // -1 until loaded; only moves up

    init {
        scope.launch(Dispatchers.Default.limitedParallelism(1)) {
            val raw = loadWithRetry()
            var tags = raw.mapValues { (_, t) -> t.copy(children = sanitizeEdges(raw, t.children)) }
            if (TAG.FAV !in tags) tags = tags + (TAG.FAV to TagNode(TAG.FAV, "Favorites", emptyList()))
            if (TAG.PINNED !in tags) tags = tags + (TAG.PINNED to TagNode(TAG.PINNED, "Pinned", emptyList()))
            val next = maxOf((tags.keys.maxOrNull() ?: 0L) + 1, FIRST_USER_TAG_ID)
            nextId.accumulateAndGet(next) { a, b -> maxOf(a, b) }
            _graph.value = _graph.value.copy(version = 1, loaded = true, tags = tags, nextTagId = next) // not persisted
            for (f in inbox) apply(f)
        }
    }

    // UI rows, computed on Default. Each is recomputed only when one of its inputs changed (by
    // identity), and published only when the result changed. Popup and tag-badge data are computed
    // on demand (appPopupOf, tagNamesOf): one item at a time, instead of all items on every change.
    val tagView: StateFlow<TagView> = project(TagView.EMPTY, { it.tags }, { it.system }, f = ::tagViewOf)
    val favorites: StateFlow<List<LauncherItem>> = tagView.derive(emptyList()) { it.rowsByTag[TAG.FAV].orEmpty() }
    val allTags: StateFlow<List<LauncherItem.Tag>> = tagView.derive(emptyList()) { it.tags.values.toList() }
    val allApps: StateFlow<Map<Char, List<LauncherItem>>> = project(emptyMap(), { it.system }, { it.tags[TAG.PINNED] }, f = ::allAppsOf)

    private fun <B> project(initial: B, vararg inputs: (Graph) -> Any?, f: (Graph) -> B): StateFlow<B> =
        graph.distinctUntilChanged { a, b -> inputs.all { it(a) === it(b) } }.derive(initial, f)

    private fun <A, B> Flow<A>.derive(initial: B, f: (A) -> B): StateFlow<B> =
        map(f).distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, initial)

    /** System snapshot from SystemSource (never persisted: its edits leave tags untouched). */
    fun dispatch(systemChange: (Graph) -> Graph): Boolean = edit(systemChange)

    /** Appends edges whose keys are not in the tag yet; tag edges must point to existing tags. */
    fun add(tagId: Long, edges: List<Edge>) = edit { g ->
        g.editTag(tagId) { t ->
            val existing = t.children.mapTo(HashSet()) { it.key }
            val add = sanitizeEdges(g.tags, edges).filter { it.key !in existing }
            if (add.isEmpty()) t else t.copy(children = t.children + add)
        }
    }

    fun pinShortcut(pkg: String, id: String, label: String?) =
        add(TAG.PINNED, listOf(Edge(ItemKey.Shortcut(pkg, id), label)))

    fun remove(tagId: Long, key: ItemKey) = edit { g ->
        g.editTag(tagId) { t -> t.copy(children = t.children.filterNot { it.key == key }) }
    }

    /**
     * Makes the tag's children [edges] (by key, in order); existing edges keep their label.
     * Only keys in [shown] (the list the user was editing) or in [edges] are managed: any other
     * child, e.g. a shortcut not resolved yet when the list was taken, stays right after the
     * child it followed before (or first), with its label.
     */
    fun setItems(tagId: Long, edges: List<Edge>, shown: Set<ItemKey>) = edit { g ->
        g.editTag(tagId) { t ->
            val byKey = t.children.associateBy { it.key }
            val next = sanitizeEdges(g.tags, edges).map { byKey[it.key] ?: it }
            val nextKeys = next.mapTo(HashSet()) { it.key }
            val keptAfter = HashMap<ItemKey?, MutableList<Edge>>()
            var anchor: ItemKey? = null
            for (e in t.children) {
                if (e.key in nextKeys) anchor = e.key
                else if (e.key !in shown) keptAfter.getOrPut(anchor) { ArrayList() } += e
            }
            if (keptAfter.isEmpty()) t.copy(children = next)
            else t.copy(children = buildList {
                keptAfter[null]?.let(::addAll)
                for (e in next) {
                    add(e)
                    keptAfter[e.key]?.let(::addAll)
                }
            })
        }
    }

    /** Ignored for system tags and blank names. */
    fun rename(tagId: Long, name: String) = edit { g ->
        if (isSystemTag(tagId) || name.isBlank()) g else g.editTag(tagId) { it.copy(name = name.trim()) }
    }

    /** Ignored for system tags. Also drops edges to the tag everywhere (like the DB's FK cascade). */
    fun delete(tagId: Long) = edit { g ->
        if (isSystemTag(tagId) || tagId !in g.tags) return@edit g
        val ref = ItemKey.Tag(tagId)
        // nextTagId is never lowered, so a deleted id is not reused in-process.
        g.copy(tags = (g.tags - tagId).mapValues { (_, t) ->
            if (t.children.any { it.key == ref }) t.copy(children = t.children.filterNot { it.key == ref }) else t
        })
    }

    /** Creates tag [id] (from [newTagId]; no-op if it exists) with [seed]; optionally appends it to Favorites. */
    fun create(id: Long, name: String, seed: List<Edge>, addToFav: Boolean) = edit { g ->
        if (id in g.tags || id < FIRST_USER_TAG_ID) return@edit g
        // A self-reference in the seed is fine (the tag exists once created); cycles are allowed.
        var tags = g.tags + (id to TagNode(id, name.trim(), sanitizeEdges(g.tags, seed, extraTagId = id)))
        val fav = tags[TAG.FAV]
        if (addToFav && fav != null && fav.children.none { it.key == ItemKey.Tag(id) }) {
            tags = tags + (TAG.FAV to fav.copy(children = fav.children + Edge(ItemKey.Tag(id))))
        }
        g.copy(tags = tags, nextTagId = maxOf(g.nextTagId, id + 1))
    }

    /** Allocates a tag id in memory; never reused in-process. Throws before the graph is loaded. */
    fun newTagId(): Long {
        while (true) {
            val cur = nextId.get()
            check(cur >= FIRST_USER_TAG_ID) { "newTagId() called before the graph was loaded" }
            if (nextId.compareAndSet(cur, cur + 1)) return cur
        }
    }

    /**
     * Waits until every edit queued before this call has run and its result is in Room. Throws
     * GraphPersistException if that write fails (the change then stays dirty in memory).
     */
    suspend fun flush() {
        val done = CompletableDeferred<Long>()
        if (!inbox.trySend { g -> done.complete(g.version); g }.isSuccess) return
        persister.flush(done.await())
    }

    private fun edit(f: (Graph) -> Graph): Boolean = inbox.trySend(f).isSuccess

    private fun apply(f: (Graph) -> Graph) {
        val old = _graph.value
        val new = try {
            f(old)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.wtf(LOG_TAG, "graph edit failed", e) // the stack names the edit's lambda
            return
        }
        if (new === old) return
        nextId.accumulateAndGet(new.nextTagId) { a, b -> maxOf(a, b) }
        val next = new.copy(version = old.version + 1)
        _graph.value = next // memory first
        persister.markDirty(old, next) // disk later (a no-op when tags are unchanged)
    }

    private suspend fun loadWithRetry(): Map<Long, TagNode> {
        var backoff = 500L
        while (true) {
            try {
                return persister.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.wtf(LOG_TAG, "graph load failed, retrying in ${backoff}ms", e)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "LauncherGraphStore"
    }
}

private fun isSystemTag(id: Long) = id == TAG.FAV || id == TAG.PINNED

/** Replaces one TagNode; returns [this] if the tag is missing or unchanged. */
private inline fun Graph.editTag(id: Long, f: (TagNode) -> TagNode): Graph {
    val t = tags[id] ?: return this
    val nt = f(t)
    return if (nt == t) this else copy(tags = tags + (id to nt))
}

/** Distinct keys (first wins), and tag edges only to existing tags. Cycles are allowed. */
private fun sanitizeEdges(tags: Map<Long, TagNode>, edges: List<Edge>, extraTagId: Long? = null): List<Edge> {
    val seen = HashSet<ItemKey>()
    return edges.filter { e ->
        val k = e.key
        (k !is ItemKey.Tag || k.id in tags || k.id == extraTagId) && seen.add(k)
    }
}

// ---------------------------------------------------------------- snapshot -> UI rows

/** Every tag as a row, and every tag's resolved children (one level). */
data class TagView(val tags: Map<Long, LauncherItem.Tag>, val rowsByTag: Map<Long, List<LauncherItem>>) {
    companion object {
        val EMPTY = TagView(emptyMap(), emptyMap())
    }
}

/**
 * Map lookups only (no IPC, no decoding). A child whose app/shortcut/tag is missing is dropped; a
 * tag's representative is its first child, following tag chains: empty -> EMPTY_TAG, unresolvable
 * -> MISSING_REFERENCE, cycle -> RECURSION. Labels: edge override, else system. Inputs: tags, system.
 */
fun tagViewOf(g: Graph): TagView {
    val tags = g.tags
    val sys = g.system

    fun leaf(e: Edge): LauncherItem? = when (val k = e.key) {
        is ItemKey.App -> sys.appByPkg[k.pkg]?.let { LauncherItem.App(it, e.labelOverride ?: it.label, it.icon) }
        is ItemKey.Shortcut -> sys.shortcut(k)?.let { LauncherItem.Shortcut(it, e.labelOverride ?: it.label, it.icon) }
        is ItemKey.Tag -> null
    }

    val repCache = HashMap<Long, LauncherItem>()
    fun representative(tagId: Long, visited: Set<Long>): LauncherItem {
        if (tagId in visited) return LauncherItem.Placeholder(PlaceholderKind.RECURSION, "Recursion")
        repCache[tagId]?.let { return it }
        val first = tags[tagId]?.children?.firstOrNull()
            ?: return LauncherItem.Placeholder(PlaceholderKind.EMPTY_TAG, "Empty tag")
        val result = when (val k = first.key) {
            is ItemKey.Tag -> representative(k.id, visited + tagId)
            else -> leaf(first) ?: LauncherItem.Placeholder(PlaceholderKind.MISSING_REFERENCE, "Missing item")
        }
        if (result !is LauncherItem.Placeholder || result.kind != PlaceholderKind.RECURSION) repCache[tagId] = result
        return result
    }

    // Pass 1: leaves per tag (so counts are known before Tag rows are built).
    val leaves: Map<Long, List<Any?>> = tags.mapValues { (_, t) -> t.children.map { e -> (e.key as? ItemKey.Tag) ?: leaf(e) } }
    val tagRows = LinkedHashMap<Long, LauncherItem.Tag>()
    for (t in tags.values.sortedBy { it.id }) {
        val count = leaves[t.id].orEmpty().count { x -> x is LauncherItem || (x is ItemKey.Tag && x.id in tags) }
        tagRows[t.id] = LauncherItem.Tag(t.id, t.name, representative(t.id, emptySet()), count)
    }
    // Pass 2: rows, tags resolved to their rows.
    val rows = tags.mapValues { (id, _) ->
        leaves[id].orEmpty().mapNotNull { x -> if (x is ItemKey.Tag) tagRows[x.id] else x as LauncherItem? }
    }
    return TagView(tagRows, rows)
}

/** All apps + PINNED shortcuts, sorted by lowercased label, grouped by first letter. Inputs: system, PINNED. */
fun allAppsOf(g: Graph): Map<Char, List<LauncherItem>> {
    val sys = g.system
    val items = sys.apps.values.mapTo(ArrayList()) { LauncherItem.App(it, it.label, it.icon) as LauncherItem }
    g.tags[TAG.PINNED]?.children.orEmpty().forEach { e ->
        val n = (e.key as? ItemKey.Shortcut)?.let(sys::shortcut) ?: return@forEach
        items += LauncherItem.Shortcut(n, e.labelOverride ?: n.label, n.icon)
    }
    // Lowercase each label once, not twice per comparison (n log n string copies).
    return items.map { it.label.lowercase() to it }.sortedBy { it.first }
        .groupBy({ it.second.label.firstOrNull()?.uppercaseChar() ?: '#' }, { it.second })
}

/**
 * Swipe popup of one package (PR 27 order): manifest shortcuts, then dynamic (each by rank), then
 * managed-list shortcut entries (PINNED tag first, then other tags by id, each in tag order, with
 * their label override), then shortcuts pinned in Android; de-duplicated by id. A user-saved label
 * (first managed edge wins) applies wherever the shortcut appears. Computed when the popup opens.
 */
fun appPopupOf(g: Graph, pkg: String): List<LauncherItem> {
    val nodes = g.system.shortcuts[pkg] ?: return emptyList()
    fun Edge.isOurs() = (key as? ItemKey.Shortcut)?.pkg == pkg
    // Scan every edge once; sort only the few tags that reference this package.
    val managed = g.tags.values.filter { t -> t.children.any { it.isOurs() } }
        .sortedWith(compareBy<TagNode> { if (it.id == TAG.PINNED) 0 else 1 }.thenBy { it.id })
        .flatMap { t -> t.children.filter { it.isOurs() } }
    val savedLabels = HashMap<String, String>()
    managed.forEach { e -> e.labelOverride?.let { savedLabels.putIfAbsent((e.key as ItemKey.Shortcut).id, it) } }
    fun item(n: ShortcutNode) = LauncherItem.Shortcut(n, savedLabels[n.id] ?: n.label, n.icon)
    val seen = HashSet<String>()
    val system = nodes.filter { it.manifest || it.dynamic }
        .sortedWith(compareBy<ShortcutNode> { if (it.manifest) 0 else 1 }.thenBy { it.rank })
        .filter { seen.add(it.id) }
    val managedNodes = managed.mapNotNull { e ->
        val id = (e.key as ItemKey.Shortcut).id
        nodes.firstOrNull { it.id == id }?.takeIf { seen.add(it.id) }
    }
    return (system + managedNodes + nodes.filter { it.pinned && seen.add(it.id) }).map(::item)
}

/** Names of the tags (by id) that contain [key]; drives the context sheet badges. Computed when the sheet opens. */
fun tagNamesOf(g: Graph, key: ItemKey): List<String> =
    g.tags.values.filter { t -> t.children.any { it.key == key } }.sortedBy { it.id }.map { it.name }
