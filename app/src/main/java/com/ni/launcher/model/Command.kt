package com.ni.launcher.model

import com.ni.launcher.TAG

/**
 * Every mutation of [LauncherGraph] is a Command handled by [reduce] on the store's single model
 * thread. [persist] = true means the store marks changed tags dirty for the [GraphPersister].
 *
 * Mapping to the AppsVM intents (pre-graph names in parentheses where they differ):
 * - (ensureItemsInTag / addItemToFavorites) -> [Add] (appends missing keys, keeps existing edges)
 * - removeItemFromParent                  -> [Remove]
 * - updateOrder / syncTagItemsToList       -> [SetItems] with `shown` (keeps children the UI didn't list)
 * - renameTag / deleteTag                 -> [Rename] / [Delete]
 * - createTagFromItem                     -> [Create] (id from LauncherGraphStore.newTagId())
 * - label override                        -> [SetLabel] (no UI yet)
 * - PinShortcutActivity insert            -> [PinShortcut]
 */
sealed interface Command {
    val persist: Boolean get() = true

    /** Initial (or reloaded) user data from the persister. Not persisted. */
    data class Loaded(val user: UserData) : Command {
        override val persist: Boolean get() = false
    }

    /** New system snapshot from SystemSource. Not persisted. */
    data class SystemChanged(val system: SystemData) : Command {
        override val persist: Boolean get() = false
    }

    /** Appends [edges] whose keys are not yet in [tagId], in order. Existing edges are untouched. */
    data class Add(val tagId: Long, val edges: List<Edge>) : Command {
        constructor(tagId: Long, edge: Edge) : this(tagId, listOf(edge))
    }

    data class Remove(val tagId: Long, val key: ItemKey) : Command

    /** New order for [tagId]: keys present in both, in [keys] order; others are dropped. */
    data class Reorder(val tagId: Long, val keys: List<ItemKey>) : Command

    /**
     * Makes [tagId]'s children exactly [edges] (by key, in order). Existing edges keep their
     * labelOverride; new keys use the edge as given.
     *
     * If [shown] is set (UI edits), only keys in [shown] (the list the user was editing) or in
     * [edges] are managed: any other existing child, e.g. a shortcut that was not resolved yet
     * when the list was taken, is kept with its label, after the child it followed before.
     */
    data class SetItems(
        val tagId: Long,
        val edges: List<Edge>,
        val shown: Set<ItemKey>? = null,
    ) : Command

    /** Ignored for system tags and blank names; name is trimmed. */
    data class Rename(val tagId: Long, val name: String) : Command

    /** Ignored for system tags. Also strips Edge(Tag(tagId)) from all tags (mirrors FK CASCADE). */
    data class Delete(val tagId: Long) : Command

    /** Creates tag [id] (no-op if it exists) with [seed]; if [addToFav], appends it to Favorites. */
    data class Create(
        val id: Long,
        val name: String,
        val seed: List<Edge>,
        val addToFav: Boolean,
    ) : Command

    /** Sets (or clears with null) the labelOverride of [key] inside [tagId]. */
    data class SetLabel(val tagId: Long, val key: ItemKey, val label: String?) : Command

    /** Appends a shortcut to TAG.PINNED (dedup by key). */
    data class PinShortcut(val pkg: String, val shortcutId: String, val label: String?) : Command {
        fun asAdd(): Add = Add(TAG.PINNED, Edge(ItemKey.Shortcut(pkg, shortcutId), label))
    }
}
