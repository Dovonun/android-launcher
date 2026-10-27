package com.ni.launcher.system

import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.util.Log
import android.util.LruCache
import androidx.annotation.WorkerThread
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

private const val LOG_TAG = "IconCache"

/**
 * 40dp icon bitmaps, decoded off the main thread, keyed so the same key always yields the same
 * [ImageBitmap] instance while it is cached:
 * - apps:      component + user + package lastUpdateTime
 * - shortcuts: pkg + id + user + lastChangedTimestamp
 *
 * Two LRUs sized in bytes. Apps get max(8 MB, maxMemory/8) because every launchable app icon is
 * referenced by the live SystemData anyway (evicting it would only cause a re-decode and a new
 * instance); shortcuts get 8 MB. Thread-safe (LruCache is synchronized); concurrent misses on the
 * same key may decode twice, but only the first put wins and is returned.
 */
class IconCache(
    private val launcherApps: LauncherApps,
    /** Target edge in px (40dp at the display density). */
    private val px: Int,
    /** Density passed to getShortcutIconDrawable. */
    private val densityDpi: Int,
    appBudgetBytes: Int = maxOf(8 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 8).toInt()),
    shortcutBudgetBytes: Int = 8 * 1024 * 1024,
) {
    private class BitmapLru(maxBytes: Int) : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val apps = BitmapLru(appBudgetBytes)
    private val shortcuts = BitmapLru(shortcutBudgetBytes)

    fun appKey(info: LauncherActivityInfo, lastUpdateTime: Long): String =
        "${info.componentName.flattenToString()}#${info.user.hashCode()}@$lastUpdateTime"

    fun shortcutKey(si: ShortcutInfo): String =
        "${si.`package`}/${si.id}#${si.userHandle.hashCode()}@${si.lastChangedTimestamp}"

    /** Cached app icon for [key] without decoding. */
    fun peekApp(key: String): ImageBitmap? = apps.get(key)

    /** Cached shortcut icon without decoding. */
    fun peekShortcut(si: ShortcutInfo): ImageBitmap? = shortcuts.get(shortcutKey(si))

    @WorkerThread
    fun app(info: LauncherActivityInfo, lastUpdateTime: Long): ImageBitmap? {
        val key = appKey(info, lastUpdateTime)
        apps.get(key)?.let { return it }
        val bmp = try {
            info.getIcon(0)?.toBitmap(px, px)?.asImageBitmap()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "app icon decode failed for ${info.componentName}", e)
            null
        } ?: return null
        return putIfAbsent(apps, key, bmp)
    }

    @WorkerThread
    fun shortcut(si: ShortcutInfo): ImageBitmap? {
        val key = shortcutKey(si)
        shortcuts.get(key)?.let { return it }
        val bmp = try {
            launcherApps.getShortcutIconDrawable(si, densityDpi)?.toBitmap(px, px)?.asImageBitmap()
        } catch (e: Exception) { // SecurityException / IllegalStateException when not default home
            Log.w(LOG_TAG, "shortcut icon decode failed for ${si.`package`}/${si.id}", e)
            null
        } ?: return null
        return putIfAbsent(shortcuts, key, bmp)
    }

    /** Drop every entry of [pkg] (package changed: icon may change without a new timestamp). */
    fun invalidatePackage(pkg: String) {
        // Both key kinds start with "<pkg>/" (flattened component or pkg/id).
        val prefix = "$pkg/"
        for (cache in listOf(apps, shortcuts)) {
            cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        }
    }

    /** Release shortcut icons first under memory pressure (apps are referenced by the graph). */
    fun trimShortcuts() = shortcuts.evictAll()

    private fun putIfAbsent(cache: BitmapLru, key: String, bmp: ImageBitmap): ImageBitmap =
        synchronized(cache) {
            cache.get(key) ?: bmp.also { cache.put(key, it) }
        }
}
