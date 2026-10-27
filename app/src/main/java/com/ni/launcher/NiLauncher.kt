package com.ni.launcher

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.ni.launcher.data.AppDatabase
import com.ni.launcher.data.RoomGraphPersister
import com.ni.launcher.model.LauncherGraphStore
import com.ni.launcher.model.LauncherProjections
import com.ni.launcher.system.SystemSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NiLauncher : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "launcher.db"
        )
            // No destructive fallback on upgrade: a version bump must ship a Migration (schemas are
            // exported to app/schemas). Downgrades (installing an older build) still reset.
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()
    }

    /** Process-lifetime scope for the graph layer. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * In-memory launcher graph: the single source of truth for tags and system data. Created once
     * in [onCreate]; loads Room on IO, then writes behind (see RoomGraphPersister).
     */
    lateinit var graphStore: LauncherGraphStore
        private set

    /** UI projections of [graphStore] (Eagerly shared in [appScope]). */
    lateinit var projections: LauncherProjections
        private set

    /** LauncherApps -> SystemChanged adapter; started in [onCreate], work runs on IO. */
    lateinit var systemSource: SystemSource
        private set

    override fun onCreate() {
        super.onCreate()
        val persister = RoomGraphPersister(database, appScope)
        graphStore = LauncherGraphStore(
            scope = appScope,
            persister = persister,
            onError = { cmd, e -> Log.wtf(LOG_TAG, "graph store error (cmd=$cmd)", e) },
        )
        projections = LauncherProjections(graphStore, appScope)
        systemSource = SystemSource(this, graphStore, appScope).also { it.start() }
    }

    /**
     * Flush pending graph writes; called from MainActivity.onStop (lifecycle-process /
     * ProcessLifecycleOwner is not a dependency). Runs in [appScope], NonCancellable.
     */
    fun flushGraphAsync(): Job = appScope.launch {
        withContext(NonCancellable) { graphStore.flush() }
    }

    private companion object {
        const val LOG_TAG = "NiLauncher"
    }
}
