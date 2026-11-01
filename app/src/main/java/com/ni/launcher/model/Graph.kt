package com.ni.launcher.model

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.ni.launcher.LauncherItem
import com.ni.launcher.TAG
import com.ni.launcher.data.RoomGraphPersister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * The in-memory launcher graph: one immutable value ([Graph]) owned by [LauncherGraphStore].
 * Plain values only (ImageBitmap is the one exception), so `equals` is structural and StateFlow
 * conflation works. Tags are persisted by RoomGraphPersister; apps and shortcuts come from
 * SystemSource and are never persisted.
 */

/** Apps are keyed by package, like tag_items. (Subtypes stay: SystemSource matches on them.) */
sealed interface ItemKey {
    data class App(val pkg: String) : ItemKey
    data class Shortcut(val pkg: String, val id: String) : ItemKey
    data class Tag(val id: Long) : ItemKey
}

data class Edge(val key: ItemKey, val labelOverride: String? = null)

/** [children] order is the persisted itemOrder. */
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

const val FIRST_USER_TAG_ID = 3L // 1 and 2 are TAG.FAV and TAG.PINNED

/**
 * One snapshot: user tags (persisted) plus system apps/shortcuts (derived). [version] is first so
 * StateFlow's equality check stops there. [apps] is keyed by component in getActivityList order;
 * [shortcuts] by package. [nextTagId] is derived (max id + 1, never below 3), not a DB column.
 */
data class Graph(
    val version: Long = 0L,
    val loaded: Boolean = false,
    val tags: Map<Long, TagNode> = emptyMap(),
    val nextTagId: Long = FIRST_USER_TAG_ID,
    val apps: Map<String, AppNode> = emptyMap(),
    val shortcuts: Map<String, List<ShortcutNode>> = emptyMap(),
) {
    /** SystemSource reads `graph.user.tags`. */
    val user: Graph get() = this

    /** First launcher activity per package; ItemKey.App edges resolve through this. */
    val appByPkg: Map<String, AppNode> by lazy {
        val out = LinkedHashMap<String, AppNode>()
        for (a in apps.values) out.putIfAbsent(a.pkg, a)
        out
    }

    companion object {
        val EMPTY = Graph()
    }
}

// SystemSource builds its snapshots as `SystemData` and reads tags as `UserData`.
typealias SystemData = Graph
typealias UserData = Graph

/** SystemSource calls `store.dispatch(Command.SystemChanged(s))`; that's all that is left of Command. */
object Command {
    @Suppress("FunctionName")
    fun SystemChanged(s: SystemData): (Graph) -> Graph = { g ->
        if (g.apps == s.apps && g.shortcuts == s.shortcuts) g else g.copy(apps = s.apps, shortcuts = s.shortcuts)
    }
}

/**
 * App-scoped single source of truth. Every edit is a lambda queued on [inbox] and run one at a
 * time on a single model thread, so the snapshot needs no locks. Edits queued before Room has
 * loaded run, in order, right after the load. After an effective edit the new snapshot is
 * published first, then (for user edits) handed to the persister, which writes it behind.
 * Edits return the same Graph when nothing changed; untouched TagNodes keep their identity, so
 * the persister diffs with `!==`.
 */
class LauncherGraphStore(private val scope: CoroutineScope, private val persister: RoomGraphPersister) {
    private val _graph = MutableStateFlow(Graph.EMPTY)
    val graph: StateFlow<Graph> = _graph

    private val inbox = Channel<() -> Unit>(Channel.UNLIMITED)
    private val nextId = AtomicLong(-1) // -1 until loaded; only moves up

    init {
        scope.launch(Dispatchers.Default.limitedParallelism(1)) {
            val raw = loadWithRetry()
            apply("load", persist = false) { g ->
                var tags = raw.mapValues { (_, t) -> t.copy(children = sanitizeEdges(raw, t.children)) }
                if (TAG.FAV !in tags) tags = tags + (TAG.FAV to TagNode(TAG.FAV, "Favorites", emptyList()))
                if (TAG.PINNED !in tags) tags = tags + (TAG.PINNED to TagNode(TAG.PINNED, "Pinned", emptyList()))
                g.copy(loaded = true, tags = tags, nextTagId = nextFreeTagId(g.copy(tags = tags)))
            }
            for (f in inbox) f()
        }
    }

    // UI projections: computed on Default, shared eagerly, deduplicated by equality.
    val tagView: StateFlow<TagView> = graph.project(TagView.EMPTY, ::tagViewOf)
    val favorites: StateFlow<List<LauncherItem>> = tagView.project(emptyList()) { it.rowsByTag[TAG.FAV].orEmpty() }
    val allTags: StateFlow<List<LauncherItem.Tag>> = tagView.project(emptyList()) { it.tags.values.toList() }
    val allApps: StateFlow<Map<Char, List<LauncherItem>>> = graph.project(emptyMap(), ::allAppsOf)
    val appPopups: StateFlow<Map<String, List<LauncherItem>>> = graph.project(emptyMap(), ::appPopupsOf)
    val tagsByKey: StateFlow<Map<ItemKey, List<String>>> = graph.project(emptyMap(), ::tagsByKeyOf)

    private fun <A, B> StateFlow<A>.project(initial: B, f: (A) -> B): StateFlow<B> =
        map(f).distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, initial)

    /** System snapshot from SystemSource; not persisted. */
    fun dispatch(systemChange: (Graph) -> Graph): Boolean = edit("system", persist = false, systemChange)

    /** Appends edges whose keys are not in the tag yet; tag edges must point to existing tags. */
    fun add(tagId: Long, edges: List<Edge>) = edit("add $tagId") { g ->
        g.editTag(tagId) { t ->
            val existing = t.children.mapTo(HashSet()) { it.key }
            val add = sanitizeEdges(g.tags, edges).filter { it.key !in existing }
            if (add.isEmpty()) t else t.copy(children = t.children + add)
        }
    }

    fun pinShortcut(pkg: String, id: String, label: String?) =
        add(TAG.PINNED, listOf(Edge(ItemKey.Shortcut(pkg, id), label)))

    fun remove(tagId: Long, key: ItemKey) = edit("remove $tagId") { g ->
        g.editTag(tagId) { t -> t.copy(children = t.children.filterNot { it.key == key }) }
    }

    /**
     * Makes the tag's children [edges] (by key, in order); existing edges keep their label.
     * Only keys in [shown] (the list the user was editing) or in [edges] are managed: any other
     * child, e.g. a shortcut not resolved yet when the list was taken, stays right after the
     * child it followed before (or first), with its label.
     */
    fun setItems(tagId: Long, edges: List<Edge>, shown: Set<ItemKey>) = edit("setItems $tagId") { g ->
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
    fun rename(tagId: Long, name: String) = edit("rename $tagId") { g ->
        if (isSystemTag(tagId) || name.isBlank()) g else g.editTag(tagId) { it.copy(name = name.trim()) }
    }

    /** Ignored for system tags. Also drops edges to the tag everywhere (like the DB's FK cascade). */
    fun delete(tagId: Long) = edit("delete $tagId") { g ->
        if (isSystemTag(tagId) || tagId !in g.tags) return@edit g
        val ref = ItemKey.Tag(tagId)
        // nextTagId is never lowered, so a deleted id is not reused in-process.
        g.copy(tags = (g.tags - tagId).mapValues { (_, t) ->
            if (t.children.any { it.key == ref }) t.copy(children = t.children.filterNot { it.key == ref }) else t
        })
    }

    /** Creates tag [id] (from [newTagId]; no-op if it exists) with [seed]; optionally appends it to Favorites. */
    fun create(id: Long, name: String, seed: List<Edge>, addToFav: Boolean) = edit("create $id") { g ->
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
        if (!inbox.trySend { done.complete(_graph.value.version) }.isSuccess) return
        persister.flush(done.await())
    }

    private fun edit(what: String, persist: Boolean = true, f: (Graph) -> Graph): Boolean =
        inbox.trySend { apply(what, persist, f) }.isSuccess

    private fun apply(what: String, persist: Boolean, f: (Graph) -> Graph) {
        val old = _graph.value
        val new = try {
            f(old)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.wtf(LOG_TAG, "graph edit failed ($what)", e)
            return
        }
        if (new === old) return
        nextId.accumulateAndGet(nextFreeTagId(new)) { a, b -> maxOf(a, b) }
        val next = new.copy(version = old.version + 1)
        _graph.value = next // memory first
        if (persist) persister.markDirty(old, next) // disk later
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

private fun nextFreeTagId(g: Graph) = maxOf(g.nextTagId, (g.tags.keys.maxOrNull() ?: 0L) + 1, FIRST_USER_TAG_ID)

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
