package com.ni.launcher

import android.content.pm.LauncherApps
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.ni.launcher.TAG.PINNED
import com.ni.launcher.data.TagEntity
import com.ni.launcher.data.TagItemEntity
import com.ni.launcher.data.TagItemType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    if (!request.accept()) return@withContext
                    // Allocate the order and insert atomically, even for concurrent pin requests.
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Don't crash the launcher process when accepting or saving the pin fails.
                Log.e(LOG_TAG, "Failed to save pinned shortcut ${shortcut.`package`}/${shortcut.id}", e)
                Toast.makeText(applicationContext, "Couldn't save pinned shortcut", Toast.LENGTH_SHORT).show()
            } finally {
                // Finishing earlier cancels lifecycleScope and can lose the managed row.
                finish()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "PinShortcutActivity"
    }
}
