package com.example.launcher.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String
)

enum class TagItemType {
    APP, SHORTCUT, TAG
}

@Entity(
    tableName = "tag_items",
    primaryKeys = ["tagId", "order"],
    foreignKeys = [ForeignKey(
        entity = TagEntity::class,
        parentColumns = ["id"],
        childColumns = ["tagId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class TagItemEntity(
    val tagId: Long,
    val order: Int,
    val type: TagItemType,
    val packageName: String? = null,
    val shortcutId: String? = null,
    val targetTagId: Long? = null,
    val labelOverride: String? = null
)

// Test if this works - written by me
@Entity(tableName = "shortcuts", primaryKeys = ["packageName", "shortcutId"])
data class ShortcutEntity(val packageName: String, val shortcutId: String)
