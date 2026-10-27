package com.ni.launcher.model

import com.ni.launcher.TAG

/*
 * Immutable in-memory launcher graph (eager graph layer, job 1).
 *
 * Pure-JVM value types: no Android framework classes, no lambdas, so `equals` is structural and
 * StateFlow conflation works. See vault: projects/ni-launcher-eager-graph-architecture.md §4.
 */

/** Identity of a graph node. Apps are keyed by package, matching today's tag_items semantics. */
sealed interface ItemKey {
    data class App(val pkg: String) : ItemKey
    data class Shortcut(val pkg: String, val id: String) : ItemKey
    data class Tag(val id: Long) : ItemKey
}

/** A child reference inside a tag; [labelOverride] mirrors `TagItemEntity.labelOverride`. */
data class Edge(val key: ItemKey, val labelOverride: String? = null)

/** A user tag. [children] order is the persisted `itemOrder` (index = order). */
data class TagNode(val id: Long, val name: String, val children: List<Edge>) {
    fun contains(key: ItemKey): Boolean = children.any { it.key == key }
}

const val FAV_TAG_NAME = "Favorites"
const val PINNED_TAG_NAME = "Pinned"

/** First id ever handed out for a user-created tag (1 and 2 are the system tags). */
const val FIRST_USER_TAG_ID = 3L

fun isSystemTag(id: Long): Boolean = id == TAG.FAV || id == TAG.PINNED

/**
 * Persisted user data: tags and their ordered children.
 * [nextTagId] is the next free tag id; it is derived (max(id)+1, never below 3), not a DB column.
 */
data class UserData(val tags: Map<Long, TagNode>, val nextTagId: Long = FIRST_USER_TAG_ID) {

    /** Next free id that is safe given the current tags. */
    fun safeNextTagId(): Long =
        maxOf(nextTagId, (tags.keys.maxOrNull() ?: 0L) + 1, FIRST_USER_TAG_ID)

    /**
     * Creates TAG.FAV ("Favorites") and TAG.PINNED ("Pinned") if missing. Existing system tags are
     * kept as-is (names are not reset), matching `ensureSystemTags`, whose insert is IGNORE.
     * Replaces `ensureSystemTags` (AppsVM.kt:88).
     */
    fun withSystemTags(): UserData {
        var t = tags
        if (TAG.FAV !in t) t = t + (TAG.FAV to TagNode(TAG.FAV, FAV_TAG_NAME, emptyList()))
        if (TAG.PINNED !in t) t = t + (TAG.PINNED to TagNode(TAG.PINNED, PINNED_TAG_NAME, emptyList()))
        return if (t === tags) this else copy(tags = t)
    }

    /**
     * Load-time normalization: drops duplicate keys within a tag (first wins), drops tag edges to
     * tags that don't exist (the DB FK makes these impossible, but keep the invariant explicit)
     * and fixes [nextTagId]. Cycles between tags are allowed (as today); readers must use a
     * visited set, see [tagRefsReachableFrom].
     */
    fun sanitized(): UserData {
        val fixed = tags.mapValues { (_, t) ->
            val clean = sanitizeEdges(t.children)
            if (clean == t.children) t else t.copy(children = clean)
        }
        val next = copy(tags = fixed).safeNextTagId()
        return if (fixed == tags && next == nextTagId) this else UserData(fixed, next)
    }

    /** Distinct keys, and tag edges only to existing tags. Order preserved, first occurrence wins. */
    internal fun sanitizeEdges(edges: List<Edge>, extraTagIds: Set<Long> = emptySet()): List<Edge> {
        val seen = HashSet<ItemKey>()
        return edges.filter { e ->
            val k = e.key
            val valid = k !is ItemKey.Tag || k.id in tags || k.id in extraTagIds
            valid && seen.add(k)
        }
    }

    /**
     * All tag ids reachable from [from] through tag edges (including [from] if it exists).
     * Cycle-safe (iterative, visited set).
     */
    fun tagRefsReachableFrom(from: Long): Set<Long> {
        val visited = LinkedHashSet<Long>()
        val stack = ArrayDeque<Long>()
        stack.addLast(from)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            val node = tags[id] ?: continue
            if (!visited.add(id)) continue
            node.children.forEach { e -> (e.key as? ItemKey.Tag)?.let { stack.addLast(it.id) } }
        }
        return visited
    }

    companion object {
        val EMPTY = UserData(emptyMap(), FIRST_USER_TAG_ID)
    }
}

/**
 * Minimal system node for a launchable app. SystemSource (job 3) fills these in and may extend
 * them (e.g. ComponentName / ImageBitmap icon); kept framework-free for now.
 */
data class AppNode(
    val pkg: String,
    val activityClass: String,
    val label: String,
)

/** Minimal system node for a shortcut; see [AppNode]. */
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
)

/** Derived system state; never persisted. Shortcuts are grouped by package. */
data class SystemData(
    val apps: Map<String, AppNode>,
    val shortcuts: Map<String, List<ShortcutNode>>,
) {
    companion object {
        val EMPTY = SystemData(emptyMap(), emptyMap())
    }
}

/**
 * One immutable snapshot. [loaded] becomes true on the first [Command.Loaded]; [version] is bumped
 * by [reduce] on every effective change.
 */
data class LauncherGraph(
    val user: UserData,
    val system: SystemData,
    val loaded: Boolean,
    val version: Long,
) {
    companion object {
        val EMPTY = LauncherGraph(UserData.EMPTY, SystemData.EMPTY, loaded = false, version = 0L)
    }
}
