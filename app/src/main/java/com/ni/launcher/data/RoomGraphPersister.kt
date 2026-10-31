package com.ni.launcher.data

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.withTransaction
import com.ni.launcher.model.GraphPersister
import com.ni.launcher.model.ItemKey
import com.ni.launcher.model.UserData
import com.ni.launcher.model.UserDataDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val LOG_TAG = "RoomGraphPersister"
const val GRAPH_PERSIST_DEBOUNCE_MS = 150L
private const val MIN_BACKOFF_MS = 500L
private const val MAX_BACKOFF_MS = 30_000L

/**
 * Write-behind mirror of the in-memory graph into Room (design doc §5). The only graph class
 * that knows Room.
 *
 * - [markDirty] is non-blocking: it merges changed/removed tag ids plus the latest UserData
 *   reference into one pending batch and wakes the writer.
 * - The writer (on [io], in the app scope) waits [GRAPH_PERSIST_DEBOUNCE_MS] to coalesce, then
 *   writes the batch's *state* (not ops) in one transaction: delete removed tags, upsert tag rows,
 *   replace each changed tag's tag_items. Idempotent, so retries and merging are safe.
 * - Transient failures keep the batch dirty and retry with exponential backoff (0.5 s .. 30 s).
 *   A constraint failure (graph/DB invariant broken) triggers one full resync of the whole
 *   UserData; if that also fails it is logged with Log.wtf, the batch stays dirty (retried on the
 *   next markDirty/flush, no automatic loop) and waiting [flush] callers get a
 *   [GraphPersistException]. The durable watermark only moves after a committed transaction.
 */
class RoomGraphPersister(
    private val db: AppDatabase,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = GRAPH_PERSIST_DEBOUNCE_MS,
) : GraphPersister {

    private class Batch(
        val user: UserData,
        val changed: Set<Long>,
        val removed: Set<Long>,
        val version: Long,
    )

    private val tagDao = db.tagDao()
    private val itemDao = db.tagItemDao()

    private val lock = Any()
    private var pending: Batch? = null // guarded by lock
    private var lastMarkedVersion = 0L // guarded by lock
    @Volatile private var urgent = false

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val persisted = MutableStateFlow(0L)

    /** Last terminal write failure; a new instance per failure. [version] = failed batch's version. */
    private class Failure(val version: Long, val cause: Throwable)
    private val failure = MutableStateFlow<Failure?>(null)

    init {
        scope.launch(io) { writerLoop() }
    }

    override suspend fun load(): UserData = withContext(io) {
        db.withTransaction {
            userDataFromEntities(tagDao.getAll(), itemDao.getAll())
        }
    }

    override fun markDirty(old: UserData, new: UserData, version: Long) {
        val diff = UserDataDiff.of(old, new)
        if (diff.changed.isEmpty() && diff.removed.isEmpty()) return
        synchronized(lock) {
            pending = merge(pending, Batch(new, diff.changed, diff.removed, version))
            if (version > lastMarkedVersion) lastMarkedVersion = version
        }
        wake.trySend(Unit)
    }

    /**
     * Suspends until every change marked with a version <= [version] is in Room. Skips the
     * debounce. Versions that were never marked (non-persisted commands) don't block.
     * Throws [GraphPersistException] if the write attempt it triggers fails terminally (batch and
     * full resync both failed); that data is still dirty in memory, not durable.
     */
    override suspend fun flush(version: Long) {
        val target = synchronized(lock) { minOf(version, lastMarkedVersion) }
        if (persisted.value >= target) return
        val seen = failure.value
        urgent = true
        wake.trySend(Unit)
        val f = combine(persisted, failure) { p, f -> if (p >= target) null else f }
            .first { it == null || (it !== seen && it.version >= target) }
            ?: return
        throw GraphPersistException("graph changes up to v$target were not saved", f.cause)
    }

    /** [newer]'s user wins; removals are sticky (tag ids are never reused). */
    private fun merge(older: Batch?, newer: Batch): Batch {
        if (older == null) return newer
        val removed = older.removed + newer.removed
        return Batch(
            user = newer.user,
            changed = (older.changed + newer.changed) - removed,
            removed = removed,
            version = maxOf(older.version, newer.version),
        )
    }

    private suspend fun writerLoop() {
        var backoff = MIN_BACKOFF_MS
        for (u in wake) {
            if (!urgent) delay(debounceMs)
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
                    db.withTransaction { writeFull(b.user) }
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

    /** Order: delete removed tags (DB cascade = reducer's Delete), upsert tag rows, replace items. */
    private suspend fun writeBatch(b: Batch) {
        if (b.removed.isNotEmpty()) tagDao.deleteByIds(b.removed.toList())
        val changed = b.changed.mapNotNull { b.user.tags[it] }
        if (changed.isEmpty()) return
        // Upsert changed tags plus every tag they reference, so FK targets (incl. ids allocated in
        // memory whose own batch may have been dropped) exist before the tag_items rows.
        val referenced = changed.asSequence()
            .flatMap { it.children.asSequence() }
            .mapNotNull { (it.key as? ItemKey.Tag)?.id }
            .filter { it !in b.changed }
            .mapNotNull { b.user.tags[it] }
        tagDao.upsertAll((changed + referenced).map { it.toEntity() }.distinctBy { it.id })
        for (tag in changed) {
            itemDao.deleteByTagId(tag.id)
            itemDao.insertAll(tag.itemEntities())
        }
    }

    /** Make Room equal to [user]: drop unknown tags, upsert all, rewrite all items. */
    private suspend fun writeFull(user: UserData) {
        val stale = tagDao.getAllIds().filter { it !in user.tags }
        if (stale.isNotEmpty()) tagDao.deleteByIds(stale)
        tagDao.upsertAll(user.tags.values.map { it.toEntity() })
        for (tag in user.tags.values) {
            itemDao.deleteByTagId(tag.id)
            itemDao.insertAll(tag.itemEntities())
        }
    }
}

/** A graph write failed terminally; the changes are still in memory but not in Room. */
class GraphPersistException(message: String, cause: Throwable) : IOException(message, cause)
