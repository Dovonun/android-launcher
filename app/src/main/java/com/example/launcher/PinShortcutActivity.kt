package com.example.launcher

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.launcher.data.PwaEntity
import com.example.launcher.data.TaggedShortcutEntity
import kotlinx.coroutines.launch

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
        lifecycleScope.launch {
            shortcutDao.insert(
                TaggedShortcutEntity(
                    packageName = shortcut.`package`, shortcutId = shortcut.id, tagId = 2
                )
            )
        }
        Log.d("Launcher", "Pinned shortcut:($shortcut.id)")
        request.accept()
        finish()
    }
}

