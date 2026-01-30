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
interface TagItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TagItemEntity)

    @Delete
    suspend fun delete(item: TagItemEntity)
}

