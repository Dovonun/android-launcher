package com.example.launcher.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TagEntity::class, TagItemEntity::class],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tagDao(): TagDao
    abstract fun tagItemDao(): TagItemDao
}
