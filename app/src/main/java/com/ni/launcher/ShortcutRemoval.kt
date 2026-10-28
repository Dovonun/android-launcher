package com.ni.launcher

import kotlinx.coroutines.sync.Mutex

internal enum class ShortcutRemovalAction { UNPIN, HIDE }

// Pin acceptance and replacement of Android's per-package pin set must not race in this process.
internal val shortcutPinMutex = Mutex()

// Published static/dynamic actions remain discoverable after unpinning; hide those instead.
internal fun requiresUnpinChoice(isPinned: Boolean, isManifest: Boolean, isDynamic: Boolean): Boolean =
    isPinned && !isManifest && !isDynamic
