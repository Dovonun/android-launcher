package com.ni.launcher.data

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import com.ni.launcher.model.Edge
import com.ni.launcher.model.Graph
import com.ni.launcher.model.ItemKey
import com.ni.launcher.model.TagNode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "RoomGraphPersister"
private const val DEBOUNCE_MS = 150L
private const val MIN_BACKOFF_MS = 500L
private const val MAX_BACKOFF_MS = 30_000L

/** A graph write failed terminally; the changes are still in memory but not in Room. */
class GraphPersistException(message: String, cause: Throwable) : IOException(message, cause)

/**
 * Write-behind mirror of the graph's tags into Room; the only graph code that knows Room.
 *
 * - [markDirty] is non-blocking: it merges the changed/removed tag ids and the latest Graph into
 *   one pending batch and wakes the writer.
 * - The writer waits [DEBOUNCE_MS] to coalesce, then writes the batch's *state* in one
 *   transaction: delete removed tags, upsert tag rows, replace each changed tag's tag_items.
 *   Idempotent, so retries and merging are safe.
 * - Transient failures keep the batch dirty and retry with backoff (0.5 s .. 30 s). A constraint
 *   failure triggers one full resync; if that fails too it is logged with Log.wtf, the batch stays
 *   dirty (retried on the next markDirty/flush) and waiting [flush] callers get a
 *   [GraphPersistException]. The persisted watermark only moves after a committed transaction.
 */
class RoomGraphPersister(private val db: AppDatabase, scope: CoroutineScope) {
    private class Batch(val graph: Graph, val changed: Set<Long>, val removed: Set<Long>, val version: Long)
    private class Failure(val version: Long, val cause: Throwable) // a new instance per failure

    private val tagDao = db.tagDao()
    private val itemDao = db.tagItemDao()

    private val lock = Any()
    private var pending: Batch? = null // guarded by lock
    private var lastMarkedVersion = 0L // guarded by lock
    @Volatile private var urgent = false

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val persisted = MutableStateFlow(0L)
    private val failure = MutableStateFlow<Failure?>(null)

    init {
        scope.launch(Dispatchers.IO) { writerLoop() }
    }

    /** Room rows -> tags, children in itemOrder. Malformed rows (missing pkg/id/target) are dropped. */
    suspend fun load(): Map<Long, TagNode> = withContext(Dispatchers.IO) {
        db.withTransaction {
            val itemsByTag = itemDao.getAll().groupBy { it.tagId }
            tagDao.getAll().associate { t ->
                val children = itemsByTag[t.id].orEmpty().sortedBy { it.itemOrder }.mapNotNull { i ->
                    val key = when (i.type) {
                        TagItemType.APP -> ItemKey.App(i.packageName ?: return@mapNotNull null)
                        TagItemType.SHORTCUT -> ItemKey.Shortcut(
                            i.packageName ?: return@mapNotNull null, i.shortcutId ?: return@mapNotNull null
                        )
                        TagItemType.TAG -> ItemKey.Tag(i.targetTagId ?: return@mapNotNull null)
                    }
                    Edge(key, i.labelOverride)
                }
                t.id to TagNode(t.id, t.name, children)
            }
        }
    }

    /** Called on the model thread after [new] is published. Tags are diffed by reference. */
    fun markDirty(old: Graph, new: Graph) {
        if (old.tags === new.tags) return // system change
        val changed = new.tags.filter { (id, t) -> old.tags[id] !== t }.keys
        val removed = old.tags.keys - new.tags.keys
        if (changed.isEmpty() && removed.isEmpty()) return
        synchronized(lock) {
            pending = merge(pending, Batch(new, changed, removed, new.version))
            if (new.version > lastMarkedVersion) lastMarkedVersion = new.version
        }
        wake.trySend(Unit)
    }

    /**
     * Suspends until every change marked with a version <= [version] is in Room, skipping the
     * debounce. Versions never marked (system changes) don't block. Throws [GraphPersistException]
     * if a write covering [version] fails terminally after this call; "pending, no new failure"
     * keeps waiting.
     */
    suspend fun flush(version: Long) {
        val target = synchronized(lock) { minOf(version, lastMarkedVersion) }
        if (persisted.value >= target) return
        val seen = failure.value
        urgent = true
        wake.trySend(Unit)
        val (p, f) = combine(persisted, failure) { p, f -> p to f }
            .first { (p, f) -> p >= target || (f != null && f !== seen && f.version >= target) }
        if (p >= target) return
        throw GraphPersistException("graph changes up to v$target were not saved", f!!.cause)
    }

    /** [newer]'s graph wins; removals are sticky (tag ids are never reused). */
    private fun merge(older: Batch?, newer: Batch): Batch {
        if (older == null) return newer
        val removed = older.removed + newer.removed
        return Batch(newer.graph, (older.changed + newer.changed) - removed, removed, maxOf(older.version, newer.version))
    }

    private suspend fun writerLoop() {
        var backoff = MIN_BACKOFF_MS
        for (u in wake) {
            if (!urgent) delay(DEBOUNCE_MS)
            urgent = false
            val b = synchronized(lock) { pending.also { pending = null } } ?: continue
            try {
                db.withTransaction { writeBatch(b) }
                markPersisted(b.version)
                backoff = MIN_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLiteConstraintException) {
                Log.e(LOG_TAG, "constraint failure writing graph batch v${b.version}; full resync", e)
                try {
                    db.withTransaction { writeFull(b.graph) }
                    markPersisted(b.version)
                    backoff = MIN_BACKOFF_MS
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    // Not durable: keep the batch dirty (retried on the next markDirty/flush) and
                    // fail waiting flush() callers instead of advancing the watermark.
                    Log.wtf(LOG_TAG, "graph/DB invariant broken; batch v${b.version} kept dirty, not persisted", e2)
                    synchronized(lock) { pending = merge(b, pending ?: b) }
                    failure.value = Failure(b.version, e2)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "graph write failed (v${b.version}), retrying in ${backoff}ms", e)
                synchronized(lock) { pending = merge(b, pending ?: b) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                wake.trySend(Unit)
            }
        }
    }

    private fun markPersisted(version: Long) {
        if (version > persisted.value) persisted.value = version
    }

    /** Delete removed tags (DB cascade = the store's delete), upsert tag rows, replace items. */
    private suspend fun writeBatch(b: Batch) {
        if (b.removed.isNotEmpty()) tagDao.deleteByIds(b.removed.toList())
        val changed = b.changed.mapNotNull { b.graph.tags[it] }
        if (changed.isEmpty()) return
        // Upsert changed tags plus every tag they reference, so FK targets (incl. ids allocated in
        // memory whose own batch may have been dropped) exist before the tag_items rows.
        val referenced = changed.asSequence()
            .flatMap { it.children.asSequence() }
            .mapNotNull { (it.key as? ItemKey.Tag)?.id }
            .filter { it !in b.changed }
            .mapNotNull { b.graph.tags[it] }
        tagDao.upsertAll((changed + referenced).map { TagEntity(it.id, it.name) }.distinctBy { it.id })
        for (tag in changed) writeItems(tag)
    }

    /** Make Room equal to [g]'s tags: drop unknown tags, upsert all, rewrite all items. */
    private suspend fun writeFull(g: Graph) {
        val stale = tagDao.getAllIds().filter { it !in g.tags }
        if (stale.isNotEmpty()) tagDao.deleteByIds(stale)
        tagDao.upsertAll(g.tags.values.map { TagEntity(it.id, it.name) })
        for (tag in g.tags.values) writeItems(tag)
    }

    private suspend fun writeItems(tag: TagNode) {
        itemDao.deleteByTagId(tag.id)
        itemDao.insertAll(tag.children.mapIndexed { i, e ->
            when (val k = e.key) {
                is ItemKey.App -> TagItemEntity(tag.id, i, TagItemType.APP, packageName = k.pkg, labelOverride = e.labelOverride)
                is ItemKey.Shortcut -> TagItemEntity(
                    tag.id, i, TagItemType.SHORTCUT, packageName = k.pkg, shortcutId = k.id, labelOverride = e.labelOverride
                )
                is ItemKey.Tag -> TagItemEntity(tag.id, i, TagItemType.TAG, targetTagId = k.id, labelOverride = e.labelOverride)
            }
        })
    }
}

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String
)

// Stored as the constant name (Room's built-in enum converter): renaming a constant needs a migration.
enum class TagItemType {
    APP, SHORTCUT, TAG
}

@Entity(
    tableName = "tag_items",
    primaryKeys = ["tagId", "itemOrder"],
    indices = [Index("targetTagId")],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetTagId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TagItemEntity(
    val tagId: Long,
    val itemOrder: Int,
    val type: TagItemType,
    val packageName: String? = null,
    val shortcutId: String? = null,
    val targetTagId: Long? = null,
    val labelOverride: String? = null
)

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

@Database(
    entities = [TagEntity::class, TagItemEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tagDao(): TagDao
    abstract fun tagItemDao(): TagItemDao
}
