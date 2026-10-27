package com.ni.launcher.model

import com.ni.launcher.TAG

/**
 * Pure reducer: no IO, no Android, no decoding. Returns the *same instance* [g] when the command
 * is a no-op (missing tag, duplicate add, unchanged order, ...); otherwise a new snapshot with
 * `version = g.version + 1`. Untouched TagNodes keep their identity (structural sharing), so the
 * persister can diff with `!==`.
 */
fun reduce(g: LauncherGraph, c: Command): LauncherGraph {
    val next: LauncherGraph = when (c) {
        is Command.Loaded -> g.copy(user = c.user.withSystemTags().sanitized(), loaded = true)
        is Command.SystemChanged -> if (c.system == g.system) g else g.copy(system = c.system)
        is Command.Add -> g.editTag(c.tagId) { u, t -> t.appended(u, c.edges) }
        is Command.PinShortcut -> return reduce(g, c.asAdd())
        is Command.Remove -> g.editTag(c.tagId) { _, t ->
            t.copy(children = t.children.filterNot { it.key == c.key })
        }
        is Command.Reorder -> g.editTag(c.tagId) { _, t ->
            val byKey = t.children.associateBy { it.key }
            val seen = HashSet<ItemKey>()
            t.copy(children = c.keys.mapNotNull { k -> if (seen.add(k)) byKey[k] else null })
        }
        is Command.SetItems -> g.editTag(c.tagId) { u, t ->
            val byKey = t.children.associateBy { it.key }
            t.copy(children = u.sanitizeEdges(c.edges).map { byKey[it.key] ?: it })
        }
        is Command.Rename -> {
            val name = c.name.trim()
            if (isSystemTag(c.tagId) || name.isEmpty()) g
            else g.editTag(c.tagId) { _, t -> t.copy(name = name) }
        }
        is Command.Delete -> if (isSystemTag(c.tagId)) g else g.deleteTag(c.tagId)
        is Command.Create -> g.createTag(c)
        is Command.SetLabel -> g.editTag(c.tagId) { _, t ->
            t.copy(children = t.children.map { if (it.key == c.key) it.copy(labelOverride = c.label) else it })
        }
    }
    return if (next === g) g else next.copy(version = g.version + 1)
}

/** Replaces exactly one TagNode; returns [this] if the tag is missing or unchanged. */
private inline fun LauncherGraph.editTag(
    id: Long,
    f: (UserData, TagNode) -> TagNode,
): LauncherGraph {
    val t = user.tags[id] ?: return this
    val nt = f(user, t)
    if (nt == t) return this
    return copy(user = user.copy(tags = user.tags + (id to nt)))
}

/** Appends edges whose keys are new; tag edges must point to existing tags. */
private fun TagNode.appended(u: UserData, edges: List<Edge>): TagNode {
    val existing = children.mapTo(HashSet()) { it.key }
    val add = u.sanitizeEdges(edges).filter { it.key !in existing }
    return if (add.isEmpty()) this else copy(children = children + add)
}

private fun LauncherGraph.deleteTag(id: Long): LauncherGraph {
    if (id !in user.tags) return this
    val ref = ItemKey.Tag(id)
    val tags = (user.tags - id).mapValues { (_, t) ->
        if (t.contains(ref)) t.copy(children = t.children.filterNot { it.key == ref }) else t
    }
    // nextTagId is never lowered, so a deleted id is not reused within the process.
    return copy(user = user.copy(tags = tags))
}

private fun LauncherGraph.createTag(c: Command.Create): LauncherGraph {
    if (c.id in user.tags || isSystemTag(c.id) || c.id < FIRST_USER_TAG_ID) return this
    // Self-reference in the seed is allowed (target exists once created); cycles are allowed.
    val seed = user.sanitizeEdges(c.seed, extraTagIds = setOf(c.id))
    var tags = user.tags + (c.id to TagNode(c.id, c.name.trim(), seed))
    if (c.addToFav) {
        val fav = tags[TAG.FAV]
        val favEdge = Edge(ItemKey.Tag(c.id))
        if (fav != null && !fav.contains(favEdge.key)) {
            tags = tags + (TAG.FAV to fav.copy(children = fav.children + favEdge))
        }
    }
    return copy(user = UserData(tags, maxOf(user.nextTagId, c.id + 1)))
}
