package com.example.launcher

import android.app.Application
import androidx.room.Room
import com.example.launcher.data.AppDatabase
import com.example.launcher.data.MIGRATION_1_2

class NiLauncher : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "launcher.db"
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
