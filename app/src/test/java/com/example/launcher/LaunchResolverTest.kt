package com.example.launcher

import org.junit.Assert.assertSame
import org.junit.Test

class LaunchResolverTest {
    @Test
    fun `placeholder is returned as launch target`() {
        val placeholder = LauncherItem.Placeholder(PlaceholderKind.EMPTY_TAG, "Empty tag")
        assertSame(placeholder, resolveLaunchTarget(placeholder))
    }

    @Test
    fun `tag resolves to placeholder representative`() {
        val placeholder = LauncherItem.Placeholder(PlaceholderKind.MISSING_REFERENCE, "Missing")
        val tag = LauncherItem.Tag(
            id = 1L,
            name = "Tag A",
            getItems = { emptyList() },
            representative = placeholder
        )

        assertSame(placeholder, resolveLaunchTarget(tag))
    }

    @Test
    fun `tag chain resolves to final non-tag representative`() {
        val placeholder = LauncherItem.Placeholder(PlaceholderKind.EMPTY_TAG, "Empty")
        val nested = LauncherItem.Tag(
            id = 2L,
            name = "Nested",
            getItems = { emptyList() },
            representative = placeholder
        )
        val root = LauncherItem.Tag(
            id = 1L,
            name = "Root",
            getItems = { listOf(nested) },
            representative = nested
        )

        assertSame(placeholder, resolveLaunchTarget(root))
    }
}
