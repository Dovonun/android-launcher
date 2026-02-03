package com.example.launcher

import android.app.Application
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.example.launcher.data.AppDatabase
import com.example.launcher.data.TagDao
import com.example.launcher.data.TagEntity
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
    private val tagDao: TagDao = mockk(relaxed = true)
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

        every { app.database } returns db
        every { db.tagDao() } returns tagDao
        every { db.tagItemDao() } returns tagItemDao
        
        every { tagDao.getAll() } returns emptyList()
        coEvery { tagDao.insert(any()) } returns 0L
        
        every { app.getSystemService(LauncherApps::class.java) } returns launcherApps
        every { launcherApps.registerCallback(any()) } returns Unit
        every { launcherApps.getActivityList(null, any()) } returns emptyList()
        every { tagItemDao.getDistinctPackages() } returns flowOf(emptyList())
        every { tagItemDao.getItemsForTag(any()) } returns flowOf(emptyList())
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
        // item should now be the TagItemEntity itself
        assertEquals(childTagId, (result[0].item as TagItemEntity).targetTagId)
    }

    @Test
    fun popupEntries_excludesRepresentative() = runTest {
        val tagId = 1L
        // Tag has 2 items
        val items = listOf(
            TagItemEntity(tagId, 0, TagItemType.APP, "pkg.representative"),
            TagItemEntity(tagId, 1, TagItemType.APP, "pkg.popup")
        )
        every { tagItemDao.getItemsForTag(tagId) } returns flowOf(items)
        
        val appRep = mockk<LauncherActivityInfo> {
            every { componentName.packageName } returns "pkg.representative"
            every { label } returns "Rep"
            every { getIcon(0) } returns mockk(relaxed = true)
        }
        val appPopup = mockk<LauncherActivityInfo> {
            every { componentName.packageName } returns "pkg.popup"
            every { label } returns "Popup Item"
            every { getIcon(0) } returns mockk(relaxed = true)
        }
        every { launcherApps.getActivityList(null, any()) } returns listOf(appRep, appPopup)

        val vm = AppsVM(app)
        testScheduler.advanceUntilIdle()

        val result = vm.popupEntries(Tag(tagId))
        
        // Should only contain the item at index 1
        assertEquals(1, result.size)
        assertEquals("Popup Item", result[0].label)
    }

    @Test
    fun updateOrder_delegatesToDao() = runTest {
        val tagId = 1L
        every { tagItemDao.getItemsForTag(tagId) } returns flowOf(emptyList())
        val vm = AppsVM(app)
        testScheduler.advanceUntilIdle()
        
        val entities = listOf(
            TagItemEntity(tagId, 0, TagItemType.APP, "pkg.a"),
            TagItemEntity(tagId, 1, TagItemType.APP, "pkg.b")
        )
        val items = entities.map { UiRow("label", null, it) }
        
        coEvery { tagItemDao.updateOrder(tagId, entities) } returns Unit
        
        vm.updateOrder(tagId, items)
        
        // verify called
        io.mockk.coVerify { tagItemDao.updateOrder(tagId, entities) }
    }

    @Test
    fun getOrCreatePopupTag_createsNewTagWithShortcuts() = runTest {
        val parentTagId = 1L
        val item = TagItemEntity(parentTagId, 0, TagItemType.APP, "pkg.a")
        
        coEvery { tagDao.insert(any()) } returns 100L // New tag ID
        coEvery { tagItemDao.insert(any()) } returns Unit
        coEvery { tagItemDao.insertAll(any()) } returns Unit
        
        // Mock shortcuts for pkg.a
        val s1 = mockk<ShortcutInfo> {
            every { id } returns "s1"
            every { `package` } returns "pkg.a"
            every { shortLabel } returns "S1"
        }
        every { launcherApps.getShortcuts(any(), any()) } returns listOf(s1)

        val vm = AppsVM(app)
        testScheduler.advanceUntilIdle()

        val resultId = vm.getOrCreatePopupTag(item)
        
        assertEquals(100L, resultId)
        
        // Verify we replaced the original item in parent tag with a TAG item
        io.mockk.coVerify { tagItemDao.insert(match { 
            it.tagId == parentTagId && it.type == TagItemType.TAG && it.targetTagId == 100L 
        }) }
        
        // Verify we added the representative and shortcuts to the new tag
        io.mockk.coVerify { tagItemDao.insertAll(match { list ->
            list.size == 2 && 
            list[0].type == TagItemType.APP && list[0].itemOrder == 0 &&
            list[1].type == TagItemType.SHORTCUT && list[1].itemOrder == 1
        }) }
    }

    @Test
    fun launch_tag_launchesRepresentative() = runTest {
        val tagId = 1L
        // The item passed to launch is typically the TagItemEntity of type TAG
        val item = TagItemEntity(100L, 0, TagItemType.TAG, targetTagId = tagId)
        
        // Tag content: Index 0 is an APP
        val tagItems = listOf(
            TagItemEntity(tagId, 0, TagItemType.APP, "pkg.rep")
        )
        every { tagItemDao.getItemsForTag(tagId) } returns flowOf(tagItems)
        
        val appRep = mockk<LauncherActivityInfo> {
            every { componentName.packageName } returns "pkg.rep"
            every { componentName.className } returns "cls.rep"
            // uiList needs label/icon to filter successfully
            every { label } returns "Rep App"
            every { getIcon(0) } returns mockk(relaxed = true)
        }
        every { launcherApps.getActivityList(null, any()) } returns listOf(appRep)
        every { launcherApps.startMainActivity(any(), any(), any(), any()) } returns Unit

        val vm = AppsVM(app)
        testScheduler.advanceUntilIdle()

        vm.launch(item)
        testScheduler.advanceUntilIdle()

        val slot = io.mockk.slot<android.content.ComponentName>()
        io.mockk.verify { 
            launcherApps.startMainActivity(capture(slot), any(), any(), any()) 
        }
        assertEquals("pkg.rep", slot.captured.packageName)
    }
}