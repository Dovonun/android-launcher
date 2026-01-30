package com.example.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TagItemEntityTest {
    @Test
    fun testTagItemEntityCreation() {
        // This will fail to compile initially, satisfying the "Red" phase
        val entity = TagItemEntity(
            tagId = 1L,
            order = 0,
            type = TagItemType.APP,
            packageName = "com.example.app",
            shortcutId = null,
            targetTagId = null,
            labelOverride = "Override"
        )
        
        assertEquals(1L, entity.tagId)
        assertEquals(0, entity.order)
        assertEquals(TagItemType.APP, entity.type)
        assertEquals("com.example.app", entity.packageName)
        assertEquals(null, entity.shortcutId)
        assertEquals(null, entity.targetTagId)
        assertEquals("Override", entity.labelOverride)
    }
}
