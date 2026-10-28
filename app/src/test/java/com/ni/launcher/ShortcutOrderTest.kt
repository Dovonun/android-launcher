package com.ni.launcher

import com.ni.launcher.data.TagItemEntity
import com.ni.launcher.data.TagItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShortcutOrderTest {
    private val pinnedTag = 2L

    private fun pin(id: String, pkg: String = "brave") =
        ShortcutDescriptor(ShortcutKey(pkg, id), isPinned = true)

    private fun row(id: String, index: Int, label: String? = null, pkg: String = "brave") =
        TagItemEntity(pinnedTag, index, TagItemType.SHORTCUT, pkg, id, labelOverride = label)

    private fun keys(rows: List<TagItemEntity>) = rows.map { it.packageName to it.shortcutId }

    @Test
    fun `saving discovered pins freezes their order and later discoveries append`() {
        val managed = listOf(row("existing", 0))
        val discovered = appendUnmanagedShortcuts(
            pinnedTag, managed, listOf(pin("second"), pin("existing"), pin("first"))
        )
        assertEquals(listOf("existing", "second", "first"), discovered.map { it.shortcutId })

        // The Manage save path persists these rows with consecutive itemOrder values.
        val saved = discovered.mapIndexed { index, item -> item.copy(itemOrder = index) }
        val afterRestart = appendUnmanagedShortcuts(
            pinnedTag, saved, listOf(pin("new"), pin("first"), pin("existing"), pin("second"))
        )
        assertEquals(listOf("existing", "second", "first", "new"), afterRestart.map { it.shortcutId })
        assertEquals(listOf(0, 1, 2, 3), afterRestart.map { it.itemOrder })
    }

    @Test
    fun `saved order survives every permutation of the platform result`() {
        val managed = listOf(row("time", 0), row("x", 1))
        val api = listOf(pin("x"), pin("time"), pin("new"))
        for (result in permutations(api)) {
            assertEquals(
                listOf("time", "x", "new"),
                appendUnmanagedShortcuts(pinnedTag, managed, result).map { it.shortcutId }
            )
        }
    }

    @Test
    fun `managed static and dynamic shortcuts retain overrides and user order`() {
        val managed = listOf(row("dynamic", 0, "My dynamic action"), row("static", 1, "My static action"))
        val api = listOf(
            ShortcutDescriptor(ShortcutKey("brave", "static"), isManifest = true, isPinned = true),
            ShortcutDescriptor(ShortcutKey("brave", "dynamic"), isDynamic = true, isPinned = true)
        )
        val result = appendUnmanagedShortcuts(pinnedTag, managed, api)
        assertEquals(managed, result)
        assertSame(managed[0], result[0])
        assertSame(managed[1], result[1])
    }

    @Test
    fun `unsaved actions use type and rank before the unsaved pinned tail`() {
        val api = listOf(
            pin("second"),
            ShortcutDescriptor(ShortcutKey("brave", "dynamic1"), isDynamic = true, rank = 1),
            ShortcutDescriptor(ShortcutKey("brave", "static1"), isManifest = true, rank = 1),
            pin("first"),
            ShortcutDescriptor(ShortcutKey("brave", "dynamic0"), isDynamic = true),
            ShortcutDescriptor(ShortcutKey("brave", "static0"), isManifest = true)
        )
        assertEquals(
            listOf("static0", "static1", "dynamic0", "dynamic1", "second", "first"),
            appendUnmanagedShortcuts(pinnedTag, emptyList(), api).map { it.shortcutId }
        )
    }

    @Test
    fun `shortcut identity includes the package and repeated platform entries are ignored`() {
        val managed = listOf(row("same-id", 0, pkg = "one"))
        val result = appendUnmanagedShortcuts(
            pinnedTag, managed, listOf(pin("same-id", "two"), pin("same-id", "one"), pin("same-id", "two"))
        )
        assertEquals(listOf("one" to "same-id", "two" to "same-id"), keys(result))
    }

    @Test
    fun `existing app and nested tag rows remain in place`() {
        val managed = listOf(
            TagItemEntity(pinnedTag, 0, TagItemType.APP, packageName = "brave"),
            TagItemEntity(pinnedTag, 1, TagItemType.TAG, targetTagId = 3)
        )
        val result = appendUnmanagedShortcuts(pinnedTag, managed, listOf(pin("new")))
        assertEquals(managed, result.take(2))
        assertEquals(2, result.last().itemOrder)
    }

    @Test
    fun `unavailable managed rows are retained and nonmatching platform entries are excluded`() {
        val managed = listOf(row("temporarily-unavailable", 0))
        assertEquals(managed, appendUnmanagedShortcuts(pinnedTag, managed, emptyList()))
        assertEquals(
            managed,
            appendUnmanagedShortcuts(pinnedTag, managed, listOf(ShortcutDescriptor(ShortcutKey("brave", "cached-only"))))
        )
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.isEmpty()) listOf(emptyList()) else items.indices.flatMap { index ->
            permutations(items.filterIndexed { i, _ -> i != index }).map { listOf(items[index]) + it }
        }
}
