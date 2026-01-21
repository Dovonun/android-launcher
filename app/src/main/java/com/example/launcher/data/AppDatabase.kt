package com.example.launcher.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TagEntity::class, TaggedAppEntity::class, TaggedShortcutEntity::class],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tagDao(): TagDao
    abstract fun taggedAppDao(): TaggedAppDao
    abstract fun taggedShortcutDao(): TaggedShortcutDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tagged_shortcuts ADD COLUMN label TEXT NOT NULL DEFAULT 'Pinned Shortcut'")
            }
        }
    }
}
