package com.ni.launcher.data

import com.ni.launcher.model.Edge
import com.ni.launcher.model.ItemKey
import com.ni.launcher.model.TagNode
import com.ni.launcher.model.UserData

/** Room rows -> UserData. Malformed rows (missing pkg/id/target) are dropped. */
fun userDataFromEntities(tags: List<TagEntity>, items: List<TagItemEntity>): UserData {
    val itemsByTag = items.groupBy { it.tagId }
    val nodes = tags.associate { t ->
        val children = itemsByTag[t.id].orEmpty()
            .sortedBy { it.itemOrder }
            .mapNotNull { it.toEdge() }
        t.id to TagNode(t.id, t.name, children)
    }
    return UserData(nodes).sanitized()
}

fun TagItemEntity.toEdge(): Edge? {
    val key: ItemKey = when (type) {
        TagItemType.APP -> ItemKey.App(packageName ?: return null)
        TagItemType.SHORTCUT -> ItemKey.Shortcut(packageName ?: return null, shortcutId ?: return null)
        TagItemType.TAG -> ItemKey.Tag(targetTagId ?: return null)
    }
    return Edge(key, labelOverride)
}

fun TagNode.toEntity(): TagEntity = TagEntity(id = id, name = name)

/** A tag's children as rows; itemOrder = index. */
fun TagNode.itemEntities(): List<TagItemEntity> = children.mapIndexed { i, e -> e.toEntity(id, i) }

fun Edge.toEntity(tagId: Long, order: Int): TagItemEntity = when (val k = key) {
    is ItemKey.App -> TagItemEntity(tagId, order, TagItemType.APP, packageName = k.pkg, labelOverride = labelOverride)
    is ItemKey.Shortcut -> TagItemEntity(
        tagId, order, TagItemType.SHORTCUT, packageName = k.pkg, shortcutId = k.id, labelOverride = labelOverride
    )
    is ItemKey.Tag -> TagItemEntity(tagId, order, TagItemType.TAG, targetTagId = k.id, labelOverride = labelOverride)
}
