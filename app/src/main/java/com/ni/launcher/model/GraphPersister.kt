package com.ni.launcher.model

/**
 * Storage hook for [LauncherGraphStore]. The Room implementation (RoomGraphPersister) is the only class
 * that knows Room: it loads once at startup and writes dirty tags behind (coalesced, ~150 ms).
 *
 * Threading contract:
 * - [load] is called once from the store's model thread; implementations switch to IO themselves.
 * - [markDirty] is called on the model thread right after a snapshot is published; it must be
 *   cheap and non-blocking (record dirty ids + latest UserData, wake the writer).
 * - [flush] suspends until everything up to [version] is durable.
 */
interface GraphPersister {
    suspend fun load(): UserData
    fun markDirty(old: UserData, new: UserData, version: Long)
    suspend fun flush(version: Long)
}

/** Ids of tags that changed (by reference) or were removed between two UserData snapshots. */
data class UserDataDiff(val changed: Set<Long>, val removed: Set<Long>) {
    companion object {
        fun of(old: UserData, new: UserData): UserDataDiff = UserDataDiff(
            changed = new.tags.filter { (id, t) -> old.tags[id] !== t }.keys,
            removed = old.tags.keys - new.tags.keys,
        )
    }
}
