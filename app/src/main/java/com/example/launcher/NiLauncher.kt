package com.example.launcher

import android.app.Application
import androidx.room.Room
import com.example.launcher.data.AppDatabase

class NiLauncher : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "launcher.db"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }
}
