package com.example.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TagItemEntityTest {
    @Test
    fun testTagItemEntityCreation() {
        // This will fail to compile initially, satisfying the "Red" phase
        val entity = TagItemEntity(
            tagId = 1L,
            itemOrder = 0,
            type = TagItemType.APP,
            packageName = "com.example.app",
            shortcutId = null,
            targetTagId = null,
            labelOverride = "Override"
        )
        
        assertEquals(1L, entity.tagId)
        assertEquals(0, entity.itemOrder)
        assertEquals(TagItemType.APP, entity.type)
        assertEquals("com.example.app", entity.packageName)
        assertEquals(null, entity.shortcutId)
        assertEquals(null, entity.targetTagId)
        assertEquals("Override", entity.labelOverride)
    }

    @Test
    fun testTagItemDaoInterface() {
        // This is mostly to define the interface we want
        // We can't easily mock the DAO here without more setup, 
        // but we can check if the methods we want are there if we were using an interface.
        // For now, let's just make sure it compiles with the new methods.
    }
}
