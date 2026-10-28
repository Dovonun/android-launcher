package com.ni.launcher

import com.ni.launcher.data.TagItemEntity
import com.ni.launcher.data.TagItemType

internal data class ShortcutKey(val packageName: String, val id: String)

// Android metadata only; ordering and persistence do not depend on framework objects.
internal data class ShortcutDescriptor(
    val key: ShortcutKey,
    val isManifest: Boolean = false,
    val isDynamic: Boolean = false,
    val isPinned: Boolean = false,
    val rank: Int = 0
)

/** Keep the supplied managed order and labels, then append shortcuts not yet managed. */
internal fun appendUnmanagedShortcuts(
    tagId: Long,
    managedRows: List<TagItemEntity>,
    shortcuts: List<ShortcutDescriptor>
): List<TagItemEntity> {
    val seen = managedRows.asSequence()
        .filter { it.type == TagItemType.SHORTCUT }
        .mapNotNull { row ->
            val pkg = row.packageName ?: return@mapNotNull null
            val id = row.shortcutId ?: return@mapNotNull null
            ShortcutKey(pkg, id)
        }.toMutableSet()
    var nextOrder = (managedRows.maxOfOrNull { it.itemOrder } ?: -1) + 1
    val additions = shortcuts
        .filter { it.isManifest || it.isDynamic || it.isPinned }
        // Stable sorting keeps Android's query order for the unsaved, pinned-only tail.
        .sortedWith(compareBy<ShortcutDescriptor> {
            when {
                it.isManifest -> 0
                it.isDynamic -> 1
                else -> 2
            }
        }.thenBy { if (it.isManifest || it.isDynamic) it.rank else 0 })
        .filter { seen.add(it.key) }
        .map {
            TagItemEntity(
                tagId = tagId,
                itemOrder = nextOrder++,
                type = TagItemType.SHORTCUT,
                packageName = it.key.packageName,
                shortcutId = it.key.id
            )
        }
    return managedRows + additions
}
