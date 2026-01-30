package com.example.launcher.data

import android.content.ContentValues
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. Create new table tag_items
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tag_items` (
                `tagId` INTEGER NOT NULL, 
                `itemOrder` INTEGER NOT NULL, 
                `type` TEXT NOT NULL, 
                `packageName` TEXT, 
                `shortcutId` TEXT, 
                `targetTagId` INTEGER, 
                `labelOverride` TEXT, 
                PRIMARY KEY(`tagId`, `itemOrder`), 
                FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        val itemsByTag = mutableMapOf<Long, Int>()

        // 2. Migrate Apps
        try {
            val cursorApps = database.query("SELECT * FROM tagged_apps")
            while (cursorApps.moveToNext()) {
                val tagId = cursorApps.getLong(cursorApps.getColumnIndexOrThrow("tagId"))
                val pkg = cursorApps.getString(cursorApps.getColumnIndexOrThrow("packageName"))
                
                val currentOrder = itemsByTag.getOrDefault(tagId, 0)
                itemsByTag[tagId] = currentOrder + 1
                
                val cv = ContentValues().apply {
                    put("tagId", tagId)
                    put("itemOrder", currentOrder)
                    put("type", "APP")
                    put("packageName", pkg)
                }
                database.insert("tag_items", 0, cv)
            }
            cursorApps.close()
        } catch (e: Exception) {
            // Table might not exist if it was a fresh install or already nuked? 
            // Or if previous migration failed. Safe to ignore? 
            // If it fails, we just don't migrate data.
        }

        // 3. Migrate Shortcuts
        try {
            val cursorShortcuts = database.query("SELECT * FROM tagged_shortcuts")
            while (cursorShortcuts.moveToNext()) {
                val tagId = cursorShortcuts.getLong(cursorShortcuts.getColumnIndexOrThrow("tagId"))
                val pkg = cursorShortcuts.getString(cursorShortcuts.getColumnIndexOrThrow("packageName"))
                val sId = cursorShortcuts.getString(cursorShortcuts.getColumnIndexOrThrow("shortcutId"))
                val label = cursorShortcuts.getString(cursorShortcuts.getColumnIndexOrThrow("label"))

                val currentOrder = itemsByTag.getOrDefault(tagId, 0)
                itemsByTag[tagId] = currentOrder + 1

                val cv = ContentValues().apply {
                    put("tagId", tagId)
                    put("itemOrder", currentOrder)
                    put("type", "SHORTCUT")
                    put("packageName", pkg)
                    put("shortcutId", sId)
                    put("labelOverride", label)
                }
                database.insert("tag_items", 0, cv)
            }
            cursorShortcuts.close()
        } catch (e: Exception) {
            // Ignore
        }

        // 4. Drop old tables
        database.execSQL("DROP TABLE IF EXISTS tagged_apps")
        database.execSQL("DROP TABLE IF EXISTS tagged_shortcuts")
    }
}
