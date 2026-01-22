package com.example.launcher

import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.launcher.TAG.PINNED
import com.example.launcher.data.TaggedShortcutEntity
import kotlinx.coroutines.launch

fun isPinned(shortcut: ShortcutInfo): Boolean {
    return shortcut.activity != null
}

class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val db = (application as NiLauncher).database
        val shortcutDao = db.taggedShortcutDao()

        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as? LauncherApps
        val request = launcherApps?.getPinItemRequest(intent)
        if (request == null) return
        if (!request.isValid) return
        val shortcut = request.shortcutInfo ?: return
        if (isPinned(shortcut)) lifecycleScope.launch {
            Log.d("Launcher", "Setting Pinned for ${shortcut.`package`}")
            shortcutDao.insert(
                TaggedShortcutEntity(
                    packageName = shortcut.`package`,
                    shortcutId = shortcut.id,
                    tagId = PINNED,
                    label = shortcut.shortLabel?.toString() ?: "Pinned Shortcut"
                )
            )
        }
        request.accept()
        finish()
    }
}

