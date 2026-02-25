package com.example.launcher.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM tags")
    // Reactive UI graph and badges subscribe to this flow.
    fun getAllFlow(): Flow<List<TagEntity>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TagItemEntity>)

    @Delete
    suspend fun delete(item: TagItemEntity)

    @Query("DELETE FROM tag_items WHERE tagId = :tagId")
    suspend fun deleteByTagId(tagId: Long)

    @Query("SELECT * FROM tag_items WHERE tagId = :tagId ORDER BY `itemOrder` ASC")
    fun getItemsForTag(tagId: Long): Flow<List<TagItemEntity>>

    @Query("SELECT * FROM tag_items ORDER BY tagId, itemOrder ASC")
    fun getAllItemsFlow(): Flow<List<TagItemEntity>>

    @Query("SELECT DISTINCT packageName FROM tag_items WHERE type = 'APP' OR type = 'SHORTCUT'")
    fun getDistinctPackages(): Flow<List<String>>

    @Query("SELECT MAX(itemOrder) FROM tag_items WHERE tagId = :tagId")
    suspend fun getMaxOrderForTag(tagId: Long): Int?

    suspend fun nextOrderForTag(tagId: Long): Int = (getMaxOrderForTag(tagId) ?: -1) + 1

    @Transaction
    suspend fun updateOrder(tagId: Long, items: List<TagItemEntity>) {
        // Replace whole ordering because (tagId, itemOrder) is the primary key.
        deleteByTagId(tagId)
        insertAll(items.mapIndexed { index, item ->
            item.copy(itemOrder = index)
        })
    }
}
