package com.example.launcher.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TagEntity::class, TaggedAppEntity::class, TaggedShortcutEntity::class],
    version = 1
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tagDao(): TagDao
    abstract fun taggedAppDao(): TaggedAppDao
    abstract fun taggedShortcutDao(): TaggedShortcutDao
}
