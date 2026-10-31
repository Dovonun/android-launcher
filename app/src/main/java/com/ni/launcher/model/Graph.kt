package com.ni.launcher.model

import androidx.compose.ui.graphics.ImageBitmap
import com.ni.launcher.TAG

/*
 * Immutable in-memory launcher graph (eager graph layer).
 *
 * Plain value types: no Android framework classes (Compose ImageBitmap is the one exception), no
 * lambdas, so `equals` is structural and StateFlow conflation works. See vault: projects/ni-launcher-eager-graph-architecture.md §4.
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
     * kept as-is (names are not reset), as the former DB-side `ensureSystemTags` (insert IGNORE) did.
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
     * visited set (see Projections.tagView).
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

    companion object {
        val EMPTY = UserData(emptyMap(), FIRST_USER_TAG_ID)
    }
}

/**
 * A launchable activity (filled by SystemSource). [component] is
 * `ComponentName.flattenToString()` and is the key in [SystemData.apps].
 * [icon] is a 40dp bitmap from IconCache; the same key yields the same instance, so `equals`
 * (reference equality for ImageBitmap) stays stable across refreshes. Null until decoded.
 */
data class AppNode(
    val pkg: String,
    val component: String,
    val label: String,
    val icon: ImageBitmap? = null,
)

/** A shortcut (manifest, dynamic or pinned) of [pkg]; see [AppNode] for [icon]. */
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
    /** `ComponentName.flattenToString()` of the shortcut's activity, if any. */
    val activity: String? = null,
    val icon: ImageBitmap? = null,
)

/**
 * Derived system state; never persisted.
 * [apps] is keyed by component (`ComponentName.flattenToString()`) in getActivityList order, so a
 * package with several launcher activities has several entries (All Apps shows each, as before).
 * [shortcuts] is grouped by package.
 */
data class SystemData(
    val apps: Map<String, AppNode>,
    val shortcuts: Map<String, List<ShortcutNode>>,
) {
    /** First launcher activity per package; tag edges (ItemKey.App) resolve through this. */
    val appByPkg: Map<String, AppNode> by lazy {
        val out = LinkedHashMap<String, AppNode>()
        for (a in apps.values) out.putIfAbsent(a.pkg, a)
        out
    }

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
