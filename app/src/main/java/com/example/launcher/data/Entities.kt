package com.example.launcher.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String
)

@Entity(
    tableName = "tagged_apps", primaryKeys = ["packageName", "tagId"], foreignKeys = [ForeignKey(
        entity = TagEntity::class,
        parentColumns = ["id"],
        childColumns = ["tagId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class TaggedAppEntity(
    val packageName: String, val tagId: Long
)

@Entity(
    tableName = "tagged_shortcuts",
    primaryKeys = ["packageName", "shortcutId", "tagId"],
    foreignKeys = [ForeignKey(
        entity = TagEntity::class,
        parentColumns = ["id"],
        childColumns = ["tagId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class TaggedShortcutEntity(
    val packageName: String, val shortcutId: String, val tagId: Long, val label: String
)

// Test if this works - written by me
@Entity(tableName = "tagged_shortcuts", primaryKeys = ["packageName", "shortcutId"])
data class ShortcutEntity(val packageName: String, val shortcutId: String)
