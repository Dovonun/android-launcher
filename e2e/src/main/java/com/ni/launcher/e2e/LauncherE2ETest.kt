package com.ni.launcher.e2e

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * End-to-end tests for the launcher running as the real home app.
 *
 * Every test starts from cleared dev-flavor data, so they are independent and safe to rerun.
 * Timeouts are generous because software-emulated (no KVM) devices are very slow.
 */
@RunWith(AndroidJUnit4::class)
class LauncherE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    /** On failure, saves a screenshot to /data/local/tmp/ni-e2e/<test>.png (pull with adb). */
    @get:Rule
    val screenshotOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            shell("mkdir -p $SHOT_DIR")
            shell("screencap -p $SHOT_DIR/${description.methodName}.png")
        }
    }

    @Before
    fun startFromCleanHome() {
        shell("setprop $HOLD_PROP ''")
        shell("pm clear $PKG")
        shell("cmd package set-home-activity $PKG/$ACTIVITY")
        goHome()
    }

    @Test
    fun launcherStartsAsHome() {
        assertEquals(PKG, device.currentPackageName)
        // A fresh install has no favorites, and the right-edge bar still opens All Apps.
        openAllApps()
        assertNotNull("All Apps should list Settings", findInAllApps(APP))
    }

    @Test
    fun openAllAppsFromRightEdgeAndFindApp() {
        openAllApps()
        val letters = letterBarLetters()
        assertTrue("Letter bar should show several letters, got $letters", letters.size >= 3)
        val app = findInAllApps(APP)
        assertNotNull("$APP should be reachable through the letter bar", app)
    }

    @Test
    fun addAppToFavoritesShowsOnHomeAndSurvivesForceStop() {
        addToFavorites(APP)
        assertNotNull("$APP should be on home after adding it, visible: ${visibleTexts()}", waitFor(By.text(APP)))

        shell("am force-stop $PKG")
        goHome()
        assertNotNull("$APP should still be a favorite after a force-stop", waitFor(By.text(APP)))
    }

    @Test
    fun swipeOnAppOpensItsShortcutPopup() {
        addToFavorites(APP)
        val row = waitFor(By.text(APP)) ?: error("$APP missing on home, visible: ${visibleTexts()}")
        val before = visibleTexts()
        val b = row.visibleBounds
        device.swipe(b.left, b.centerY(), b.left + device.displayWidth / 2, b.centerY(), 10)
        // Settings declares manifest shortcuts (Wi-Fi, battery, data usage...).
        val opened = waitUntil { (visibleTexts() - before - APP).isNotEmpty() } == true
        assertTrue("Shortcut popup should show new entries, before=$before after=${visibleTexts()}", opened)
        device.pressBack()
        assertTrue(
            "Back should close the popup",
            waitUntil { (visibleTexts() - before).isEmpty() } == true
        )
    }

    /**
     * Staged startup publishes apps (stage 1) long before shortcuts (stage 4). Opening and
     * confirming Manage Tag in that window must not delete the tag's saved shortcut children.
     */
    @Test
    fun manageTagBeforeShortcutsLoadKeepsSavedShortcuts() {
        waitForShortcutsLoaded()
        openAllApps()
        val app = findInAllApps(APP) ?: error("$APP not found in All Apps")
        longPress(app)
        (waitFor(By.text("Create Tag")) ?: error("context sheet did not open")).click()
        assertNotNull("Manage Tag did not open", waitFor(By.desc("Confirm")))
        val saved = manageRows()
        assertTrue("$TAG_NAME should hold $APP plus shortcuts, got $saved", saved.size >= 2)
        confirmManage()

        // Cold start with shortcut discovery held (debug-only hook in SystemSource), then open
        // Manage while only apps are known, and confirm it.
        shell("setprop $HOLD_PROP DEBUG")
        shell("am force-stop $PKG")
        goHome()
        // Home shows a tag by its representative (first child).
        longPress(waitFor(By.text(APP)) ?: error("$TAG_NAME ($APP) missing on home"))
        (waitFor(By.text("Manage $TAG_NAME")) ?: error("tag sheet did not open")).click()
        assertNotNull("Manage Tag did not open", waitFor(By.desc("Confirm")))
        val early = manageRows()
        confirmManage()
        val held = shell("logcat -d --pid=${shell("pidof $PKG").trim()} -s NiE2EHoldShortcuts:W")
        assertTrue("Shortcut discovery should have been held, log: $held", held.contains("holding shortcut discovery"))
        assertTrue("Manage should open with only $APP resolved, got $early", early.size < saved.size)
        shell("setprop $HOLD_PROP ''")
        waitForShortcutsLoaded()

        // Restart again and check membership/order after everything is resolved.
        shell("am force-stop $PKG")
        goHome()
        waitForShortcutsLoaded()
        // Home shows a tag by its representative (first child).
        longPress(waitFor(By.text(APP)) ?: error("$TAG_NAME ($APP) missing on home"))
        (waitFor(By.text("Manage $TAG_NAME")) ?: error("tag sheet did not open")).click()
        assertNotNull("Manage Tag did not open", waitFor(By.desc("Confirm")))
        assertEquals("Saved children after early Manage (early view was $early)", saved, manageRows())
    }

    // --- helpers ---

    private fun longPress(o: UiObject2) {
        val b = o.visibleBounds
        device.swipe(b.centerX(), b.centerY(), b.centerX(), b.centerY(), 300)
    }

    /** Manage Tag row labels, top to bottom (the add row and buttons have no text). */
    private fun manageRows(): List<String> {
        repeat(5) {
            device.waitForIdle(5_000)
            try {
                return device.findObjects(By.pkg(PKG))
                    .mapNotNull { o -> o.text?.takeIf { it.isNotBlank() }?.let { o.visibleBounds.top to it } }
                    .sortedBy { it.first }
                    .map { it.second }
            } catch (_: StaleObjectException) {
                // The list recomposed while it was read; read it again.
            }
        }
        error("Manage Tag rows kept changing")
    }

    private fun confirmManage() {
        (waitFor(By.desc("Confirm")) ?: error("Confirm missing")).click()
        assertTrue("Should leave Manage Tag", waitUntil { !device.hasObject(By.desc("Confirm")) } == true)
    }

    /** The running launcher process logs "stage 4 shortcuts" (tag NiGraph) once they are published. */
    private fun waitForShortcutsLoaded() {
        assertTrue(
            "shortcuts never loaded",
            waitUntil(LONG * 2) {
                val pid = shell("pidof $PKG").trim()
                pid.isNotEmpty() && shell("logcat -d --pid=$pid -s NiGraph:D").contains("stage 4 shortcuts")
            } == true
        )
    }

    private fun shell(cmd: String): String = device.executeShellCommand(cmd)

    private fun goHome() {
        device.pressHome()
        assertTrue("Launcher should be foreground", waitUntil { device.hasObject(By.pkg(PKG).depth(0)) } == true)
        device.waitForIdle(5_000)
    }

    private fun waitFor(selector: BySelector, timeout: Long = LONG): UiObject2? =
        waitUntil(timeout) { device.findObject(selector) }

    /** Polls [block] until non-null/true, tapping "Wait" on emulator ANR dialogs meanwhile. */
    private fun <T> waitUntil(timeout: Long = LONG, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeout
        while (true) {
            device.findObject(By.pkg("android").text("Wait"))?.click()
            val result = block()
            if (result != null && result != false) return result
            if (System.currentTimeMillis() > end) return null
            Thread.sleep(500)
        }
    }

    private fun visibleTexts(): Set<String> =
        device.findObjects(By.pkg(PKG)).mapNotNull { it.text }.filter { it.isNotBlank() }.toSet()

    /** The letter bar sits on the right edge between 1/3 and 7/8 of the screen height. */
    private fun barTouchPoint(): Pair<Int, Int> =
        device.displayWidth - 20 to (device.displayHeight * 0.4).toInt()

    private fun openAllApps() {
        val (x, y) = barTouchPoint()
        device.click(x, y)
        assertTrue(
            "Letter bar should appear after touching the right edge",
            waitUntil { letterBarLetters().isNotEmpty() } == true
        )
    }

    private fun letterBarLetters(): List<UiObject2> {
        val edge = device.displayWidth * 3 / 4
        return device.findObjects(By.pkg(PKG).text(SINGLE_LETTER))
            .filter { it.visibleBounds.left > edge }
    }

    /** Touches the label's first letter on the bar, then waits for the label in the list. */
    private fun findInAllApps(label: String): UiObject2? {
        val letter = letterBarLetters().firstOrNull { it.text == label.first().uppercase() }
            ?: return null
        val c = letter.visibleBounds.let { Rect(it) }
        // A short press-and-hold is more reliable than a tap on a slow emulator.
        device.swipe(c.centerX(), c.centerY(), c.centerX(), c.centerY() + 1, 40)
        return waitUntil {
            device.findObjects(By.pkg(PKG).text(label))
                .firstOrNull { it.visibleBounds.left < device.displayWidth / 2 }
        }
    }

    private fun addToFavorites(label: String) {
        openAllApps()
        val row = findInAllApps(label) ?: error("$label not found in All Apps")
        val b = row.visibleBounds
        device.swipe(b.centerX(), b.centerY(), b.centerX(), b.centerY(), 300) // long-press
        val add = waitFor(By.text("Add to Favorites")) ?: error("context sheet did not open")
        add.click()
        val confirm = waitFor(By.desc("Confirm")) ?: error("Manage Favorites did not open")
        confirm.click()
        assertTrue(
            "Should return home after confirming",
            waitUntil { !device.hasObject(By.desc("Confirm")) } == true
        )
    }

    private companion object {
        const val PKG = "com.ni.launcher.dev"
        const val ACTIVITY = "com.ni.launcher.MainActivity"
        const val APP = "Settings"
        const val TAG_NAME = "$APP Tag"
        const val HOLD_PROP = "log.tag.NiE2EHoldShortcuts"
        const val LONG = 60_000L
        const val SHOT_DIR = "/data/local/tmp/ni-e2e"
        val SINGLE_LETTER: Pattern = Pattern.compile("[A-Z#]")
    }
}
