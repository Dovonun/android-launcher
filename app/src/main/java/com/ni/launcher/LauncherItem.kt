package com.ni.launcher

import androidx.compose.ui.graphics.ImageBitmap
import com.ni.launcher.model.AppNode
import com.ni.launcher.model.Edge
import com.ni.launcher.model.ItemKey
import com.ni.launcher.model.ShortcutNode

/**
 * UI row model, produced by [com.ni.launcher.model.Projections] from graph snapshots.
 * Plain values (no lambdas, no framework objects), so `equals` works and StateFlows dedupe.
 */
sealed interface LauncherItem {
    val label: String
    val icon: ImageBitmap?

    data class App(
        val node: AppNode,
        override val label: String,
        override val icon: ImageBitmap?
    ) : LauncherItem

    data class Shortcut(
        val node: ShortcutNode,
        override val label: String,
        override val icon: ImageBitmap?
    ) : LauncherItem

    /**
     * A tag row. Its children are not embedded (they'd recurse); read them with
     * `AppsVM.tagItems(id)`. [count] = number of resolvable children.
     */
    data class Tag(
        val id: Long,
        val name: String,
        val representative: LauncherItem,
        val count: Int = 0,
    ) : LauncherItem {
        // Tag rows intentionally mirror the representative for list UX.
        override val label: String get() = representative.label
        override val icon: ImageBitmap? get() = representative.icon
    }

    data class Placeholder(
        val kind: PlaceholderKind,
        override val label: String,
        override val icon: ImageBitmap? = null
    ) : LauncherItem
}

enum class PlaceholderKind { EMPTY_TAG, RECURSION, MISSING_REFERENCE }

data class SheetAction(val label: String, val onTap: () -> Unit)

object TAG {
    const val FAV: Long = 1
    const val PINNED: Long = 2
}

/** Graph identity of a UI item; null for placeholders. */
fun LauncherItem.itemKey(): ItemKey? = when (this) {
    is LauncherItem.App -> ItemKey.App(node.pkg)
    is LauncherItem.Shortcut -> ItemKey.Shortcut(node.pkg, node.id)
    is LauncherItem.Tag -> ItemKey.Tag(id)
    is LauncherItem.Placeholder -> null
}

/** Edge to store when adding this item to a tag. Shortcuts keep their label as override (as before). */
fun LauncherItem.toEdge(): Edge? = when (this) {
    is LauncherItem.App -> Edge(ItemKey.App(node.pkg))
    is LauncherItem.Shortcut -> Edge(ItemKey.Shortcut(node.pkg, node.id), labelOverride = label)
    is LauncherItem.Tag -> Edge(ItemKey.Tag(id))
    is LauncherItem.Placeholder -> null
}

internal fun resolveLaunchTarget(item: LauncherItem): LauncherItem {
    var current = item
    val visitedTagIds = mutableSetOf<Long>()
    while (current is LauncherItem.Tag) {
        if (!visitedTagIds.add(current.id)) {
            error("Tag representative cycle detected while launching tagId=${current.id}")
        }
        current = current.representative
    }
    return current
}
