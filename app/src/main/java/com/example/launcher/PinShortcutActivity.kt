package com.example.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.launcher.TAG.PINNED
import com.example.launcher.data.TagItemEntity
import com.example.launcher.data.TagItemType
import kotlinx.coroutines.launch

class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val db = (application as NiLauncher).database
        val tagItemDao = db.tagItemDao()

        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as? LauncherApps
        val request = launcherApps?.getPinItemRequest(intent)
        if (request == null) return
        if (!request.isValid) return
        val shortcut = request.shortcutInfo ?: return
        lifecycleScope.launch {
            val nextOrder = tagItemDao.nextOrderForTag(PINNED)
            tagItemDao.insert(
                TagItemEntity(
                    tagId = PINNED,
                    itemOrder = nextOrder,
                    type = TagItemType.SHORTCUT,
                    packageName = shortcut.`package`,
                    shortcutId = shortcut.id,
                    labelOverride = shortcut.shortLabel?.toString()
                )
            )
        }
        request.accept()
        finish()
    }
}
