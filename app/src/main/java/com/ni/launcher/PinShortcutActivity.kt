package com.ni.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.ni.launcher.model.Command
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles CONFIRM_PIN_SHORTCUT: accepts the request, then adds the shortcut to the PINNED tag
 * through the graph store (a shortcut already in PINNED keeps its existing edge) and waits for it
 * to be persisted. The save runs in the app scope so it outlives this activity, which finishes
 * immediately on every path.
 */
class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as? LauncherApps
            val request = launcherApps?.getPinItemRequest(intent)
            if (request == null || !request.isValid) return
            val shortcut = request.shortcutInfo ?: return
            val app = application as NiLauncher
            val accepted = request.accept()
            if (!accepted) return
            app.appScope.launch {
                try {
                    val queued = app.graphStore.dispatch(
                        Command.PinShortcut(shortcut.`package`, shortcut.id, shortcut.shortLabel?.toString())
                    )
                    check(queued) { "graph store inbox closed" }
                    withContext(NonCancellable) { app.graphStore.flush() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Don't crash the launcher process when saving the pin fails.
                    Log.e(LOG_TAG, "Failed to save pinned shortcut ${shortcut.`package`}/${shortcut.id}", e)
                    withContext(Dispatchers.Main) { showSaveFailed(app) }
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Pin request failed", e)
            showSaveFailed(applicationContext)
        } finally {
            finish()
        }
    }

    private companion object {
        const val LOG_TAG = "PinShortcutActivity"

        fun showSaveFailed(context: android.content.Context) {
            Toast.makeText(context, "Couldn't save pinned shortcut", Toast.LENGTH_SHORT).show()
        }
    }
}
