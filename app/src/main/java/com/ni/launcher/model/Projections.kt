package com.ni.launcher.model

import com.ni.launcher.LauncherItem
import com.ni.launcher.PlaceholderKind
import com.ni.launcher.TAG

/** Every tag as a row, and every tag's resolved children (one level). */
data class TagView(val tags: Map<Long, LauncherItem.Tag>, val rowsByTag: Map<Long, List<LauncherItem>>) {
    companion object {
        val EMPTY = TagView(emptyMap(), emptyMap())
    }
}

/**
 * Snapshot -> UI rows; map lookups only (no IPC, no decoding). A child whose app/shortcut/tag is
 * missing is dropped; a tag's representative is its first child, following tag chains: empty ->
 * EMPTY_TAG, unresolvable -> MISSING_REFERENCE, cycle -> RECURSION. Labels: edge override, else system.
 */
fun tagViewOf(g: Graph): TagView {
    val tags = g.tags

    fun leaf(e: Edge): LauncherItem? = when (val k = e.key) {
        is ItemKey.App -> g.appByPkg[k.pkg]?.let { LauncherItem.App(it, e.labelOverride ?: it.label, it.icon) }
        is ItemKey.Shortcut -> findShortcut(g, k)?.let {
            LauncherItem.Shortcut(it, e.labelOverride ?: it.label, it.icon)
        }
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
        if (result !is LauncherItem.Placeholder || result.kind != PlaceholderKind.RECURSION) {
            repCache[tagId] = result
        }
        return result
    }

    // Pass 1: leaves per tag (so counts are known before Tag rows are built).
    val leaves: Map<Long, List<Any?>> = tags.mapValues { (_, t) ->
        t.children.map { e -> (e.key as? ItemKey.Tag) ?: leaf(e) }
    }
    val tagRows = LinkedHashMap<Long, LauncherItem.Tag>()
    for (t in tags.values.sortedBy { it.id }) {
        val count = leaves[t.id].orEmpty().count { x -> x is LauncherItem || (x is ItemKey.Tag && x.id in tags) }
        tagRows[t.id] = LauncherItem.Tag(t.id, t.name, representative(t.id, emptySet()), count)
    }
    // Pass 2: rows, tags resolved to their rows.
    val rows = tags.mapValues { (id, _) ->
        leaves[id].orEmpty().mapNotNull { x ->
            when (x) {
                is LauncherItem -> x
                is ItemKey.Tag -> tagRows[x.id]
                else -> null
            }
        }
    }
    return TagView(tagRows, rows)
}

/** All apps + PINNED shortcuts, sorted by label, grouped by first letter. */
fun allAppsOf(g: Graph): Map<Char, List<LauncherItem>> {
    val apps = g.apps.values.map { LauncherItem.App(it, it.label, it.icon) }
    val pinned = g.tags[TAG.PINNED]?.children.orEmpty().mapNotNull { e ->
        val k = e.key as? ItemKey.Shortcut ?: return@mapNotNull null
        findShortcut(g, k)?.let { LauncherItem.Shortcut(it, e.labelOverride ?: it.label, it.icon) }
    }
    return (apps + pinned).sortedBy { it.label.lowercase() }
        .groupBy { it.label.firstOrNull()?.uppercaseChar() ?: '#' }
}

/**
 * Swipe popup per package (PR 27 order): manifest shortcuts, then dynamic (each by rank), then
 * managed-list shortcut entries (PINNED tag first, then other tags by id, each in tag order,
 * with their label override), then shortcuts pinned in Android; de-duplicated by id.
 * A user-saved label (first managed edge wins) applies wherever the shortcut appears.
 */
fun appPopupsOf(g: Graph): Map<String, List<LauncherItem>> {
    val managedByPkg: Map<String, List<Edge>> = g.tags.values
        .sortedWith(compareBy<TagNode> { if (it.id == TAG.PINNED) 0 else 1 }.thenBy { it.id })
        .flatMap { t -> t.children.filter { it.key is ItemKey.Shortcut } }
        .groupBy { (it.key as ItemKey.Shortcut).pkg }
    return g.shortcuts.mapValues { (pkg, nodes) ->
        val managed = managedByPkg[pkg].orEmpty()
        val savedLabels = HashMap<String, String>()
        managed.forEach { e ->
            val label = e.labelOverride ?: return@forEach
            savedLabels.putIfAbsent((e.key as ItemKey.Shortcut).id, label)
        }
        fun item(n: ShortcutNode) = LauncherItem.Shortcut(n, savedLabels[n.id] ?: n.label, n.icon)
        val seen = HashSet<String>()
        val system = nodes
            .filter { it.manifest || it.dynamic }
            .sortedWith(compareBy<ShortcutNode> { if (it.manifest) 0 else 1 }.thenBy { it.rank })
            .filter { seen.add(it.id) }
            .map(::item)
        val managedItems = managed.mapNotNull { e ->
            val id = (e.key as ItemKey.Shortcut).id
            val node = nodes.firstOrNull { it.id == id } ?: return@mapNotNull null
            if (!seen.add(node.id)) return@mapNotNull null
            item(node)
        }
        val pinned = nodes
            .filter { it.pinned && seen.add(it.id) }
            .map(::item)
        system + managedItems + pinned
    }
}

/** Names of the tags (by id) that contain each key; drives the context sheet badges. */
fun tagsByKeyOf(g: Graph): Map<ItemKey, List<String>> {
    val out = HashMap<ItemKey, MutableList<String>>()
    for (t in g.tags.values.sortedBy { it.id }) {
        t.children.mapTo(LinkedHashSet()) { it.key }.forEach { k -> out.getOrPut(k) { ArrayList() } += t.name }
    }
    return out
}

private fun findShortcut(g: Graph, k: ItemKey.Shortcut): ShortcutNode? =
    g.shortcuts[k.pkg]?.firstOrNull { it.id == k.id }
