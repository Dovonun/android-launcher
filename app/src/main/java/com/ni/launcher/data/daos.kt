package com.ni.launcher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

// One-shot queries used only by RoomGraphPersister. The in-memory graph is the source of truth;
// Room is read once at startup and written behind.

@Dao
interface TagDao {
    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT id FROM tags")
    suspend fun getAllIds(): List<Long>

    // Upsert = insert or UPDATE (not REPLACE), so existing rows are never deleted and the
    // tag_items FK cascade is not triggered.
    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Query("DELETE FROM tags WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface TagItemDao {
    @Query("SELECT * FROM tag_items ORDER BY tagId, itemOrder ASC")
    suspend fun getAll(): List<TagItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TagItemEntity>)

    @Query("DELETE FROM tag_items WHERE tagId = :tagId")
    suspend fun deleteByTagId(tagId: Long)
}
