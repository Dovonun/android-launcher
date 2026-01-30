package com.example.launcher

import android.app.Application
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.example.launcher.data.AppDatabase
import com.example.launcher.data.TagDao
import com.example.launcher.data.TagItemDao
import com.example.launcher.data.TagItemEntity
import com.example.launcher.data.TagItemType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppsVMTest {
    private val app: NiLauncher = mockk(relaxed = true)
    private val db: AppDatabase = mockk()
    private val tagItemDao: TagItemDao = mockk()
    private val launcherApps: LauncherApps = mockk()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.os.Process::class)
        mockkStatic("androidx.core.graphics.drawable.DrawableKt")
        mockkStatic("androidx.compose.ui.graphics.AndroidImageBitmap_androidKt")
        
        every { any<android.graphics.drawable.Drawable>().toBitmap(any(), any(), any()) } returns mockk(relaxed = true)
        every { any<android.graphics.Bitmap>().asImageBitmap() } returns mockk(relaxed = true)

        every { android.os.Process.myUserHandle() } returns mockk()

        val tagDao: TagDao = mockk(relaxed = true)
        every { app.database } returns db
        every { db.tagDao() } returns tagDao
        every { tagDao.getAll() } returns emptyList()
        coEvery { tagDao.insert(any()) } returns 0L
        
        every { db.tagItemDao() } returns tagItemDao
        every { app.getSystemService(LauncherApps::class.java) } returns launcherApps
        every { launcherApps.registerCallback(any()) } returns Unit
        every { launcherApps.getActivityList(null, any()) } returns emptyList()
        every { tagItemDao.getDistinctPackages() } returns flowOf(emptyList())
        every { tagItemDao.getItemsForTag(2L) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun uiList_filtersByTag() = runTest {
        val tagId = 1L
        val items = listOf(
            TagItemEntity(tagId, 0, TagItemType.APP, "pkg.a"),
            TagItemEntity(tagId, 1, TagItemType.APP, "pkg.b")
        )
        every { tagItemDao.getItemsForTag(tagId) } returns flowOf(items)
        
        // Mock apps
        val appA = mockk<LauncherActivityInfo> {
            every { componentName.packageName } returns "pkg.a"
            every { label } returns "App A"
            every { getIcon(0) } returns mockk(relaxed = true)
        }
        val appB = mockk<LauncherActivityInfo> {
            every { componentName.packageName } returns "pkg.b"
            every { label } returns "App B"
            every { getIcon(0) } returns mockk(relaxed = true)
        }
        every { launcherApps.getActivityList(null, any()) } returns listOf(appA, appB)

        val vm = AppsVM(app)
        // Advance until idling to let init blocks run
        testScheduler.advanceUntilIdle()

        val result = vm.uiList(tagId).first()
        
        assertEquals(2, result.size)
        assertEquals("App A", result[0].label)
        assertEquals("App B", result[1].label)
    }

    @Test
    fun uiList_representativeInheritance() = runTest {
        val parentTagId = 1L
        val childTagId = 10L
        
        // Parent tag has one item which is a TAG type pointing to childTagId
        val parentItems = listOf(
            TagItemEntity(parentTagId, 0, TagItemType.TAG, targetTagId = childTagId)
        )
        // Child tag has one item which is an APP
        val childItems = listOf(
            TagItemEntity(childTagId, 0, TagItemType.APP, "pkg.child")
        )
        
        every { tagItemDao.getItemsForTag(parentTagId) } returns flowOf(parentItems)
        every { tagItemDao.getItemsForTag(childTagId) } returns flowOf(childItems)
        
        val childApp = mockk<LauncherActivityInfo> {
            every { componentName.packageName } returns "pkg.child"
            every { label } returns "Child App"
            every { getIcon(0) } returns mockk(relaxed = true)
        }
        every { launcherApps.getActivityList(null, any()) } returns listOf(childApp)

        val vm = AppsVM(app)
        testScheduler.advanceUntilIdle()

        val result = vm.uiList(parentTagId).first()
        
        assertEquals(1, result.size)
        // Should inherit name and icon from the child app at index 0
        assertEquals("Child App", result[0].label)
        // item should still be the Tag object so we can swipe it to open popup
        assertEquals(childTagId, (result[0].item as Tag).id)
    }
}