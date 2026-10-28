package com.ni.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutRemovalTest {
    @Test
    fun `pinned-only shortcuts offer unpin or hide`() {
        assertTrue(requiresUnpinChoice(isPinned = true, isManifest = false, isDynamic = false))
    }

    @Test
    fun `published actions are hidden even when also pinned`() {
        for (pinned in listOf(false, true)) {
            assertFalse(requiresUnpinChoice(pinned, isManifest = true, isDynamic = false))
            assertFalse(requiresUnpinChoice(pinned, isManifest = false, isDynamic = true))
            assertFalse(requiresUnpinChoice(pinned, isManifest = true, isDynamic = true))
        }
    }

    @Test
    fun `an unpinned unpublished entry does not offer unpin`() {
        assertFalse(requiresUnpinChoice(isPinned = false, isManifest = false, isDynamic = false))
    }
}
