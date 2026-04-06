package com.ni.launcher

import android.app.Application
import androidx.room.Room
import com.ni.launcher.data.AppDatabase

class NiLauncher : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "launcher.db"
        )
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }
}
