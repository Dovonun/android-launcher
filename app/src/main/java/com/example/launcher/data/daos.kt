package com.example.launcher.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM tags")
    fun getAll(): List<TagEntity>

    // Utility: insert or get existing tag
    suspend fun insertOrGet(name: String): TagEntity {
        val existing = getByName(name)
        return existing ?: run {
            val id = insert(TagEntity(name = name))
            TagEntity(id = id, name = name)
        }
    }
}

@Dao
interface TaggedAppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TaggedAppEntity)

    @Delete
    suspend fun delete(entity: TaggedAppEntity)

    @Query("SELECT packageName FROM tagged_apps WHERE tagId = :tagId")
    fun getPackagesForTag(tagId: Long): Flow<List<String>>

    @Query(
        """
        SELECT tagged_apps.packageName FROM tagged_apps
        INNER JOIN tags ON tags.id = tagged_apps.tagId
        WHERE tags.name = :tagName
        """
    )
    fun getPackagesForTag(tagName: String): Flow<List<String>>
}

@Dao
interface TaggedShortcutDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TaggedShortcutEntity)

    @Delete
    suspend fun delete(entity: TaggedShortcutEntity)

    // Does this work? I use packageName and shortcutId as props to the ShortcutEntity
    @Query("SELECT packageName, shortcutId FROM tagged_shortcuts WHERE tagId = :tagId")
    fun getShortcutsForTag(tagId: Long): Flow<List<ShortcutEntity>>

    @Query("SELECT DISTINCT packageName FROM tagged_shortcuts")
    fun getDistinctPackages(): Flow<List<String>>

    @Query(
        """
        SELECT packageName || ':' || shortcutId FROM tagged_shortcuts
        INNER JOIN tags ON tags.id = tagged_shortcuts.tagId
        WHERE tags.name = :tagName
        """
    )
    fun getShortcutsForTag(tagName: String): Flow<List<String>>
}

