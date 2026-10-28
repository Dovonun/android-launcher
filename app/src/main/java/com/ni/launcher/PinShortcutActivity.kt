package com.ni.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.ni.launcher.TAG.PINNED
import com.ni.launcher.data.TagEntity
import com.ni.launcher.data.TagItemEntity
import com.ni.launcher.data.TagItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val db = (application as NiLauncher).database
        val tagItemDao = db.tagItemDao()

        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as? LauncherApps
        val request = launcherApps?.getPinItemRequest(intent)
        if (request == null || !request.isValid) {
            finish()
            return
        }
        val shortcut = request.shortcutInfo
        if (shortcut == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    shortcutPinMutex.withLock {
                        if (!request.accept()) return@withLock
                        // Serialize order allocation with the insert, even for concurrent pin requests.
                        db.withTransaction {
                            db.tagDao().insert(TagEntity(PINNED, "Pinned"))
                            tagItemDao.insert(
                                TagItemEntity(
                                    tagId = PINNED,
                                    itemOrder = tagItemDao.nextOrderForTag(PINNED),
                                    type = TagItemType.SHORTCUT,
                                    packageName = shortcut.`package`,
                                    shortcutId = shortcut.id,
                                    labelOverride = shortcut.shortLabel?.toString()
                                )
                            )
                        }
                    }
                }
            } finally {
                // Finishing earlier cancels lifecycleScope and can lose the managed row.
                finish()
            }
        }
    }
}
