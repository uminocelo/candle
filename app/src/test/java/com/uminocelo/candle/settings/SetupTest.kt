package com.uminocelo.candle.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupTest {
    private val pkg = "com.uminocelo.candle"

    @Test
    fun `all granted - nothing missing, no first run`() {
        val state = SetupState(files = true, home = true, overlay = true, remote = true)
        assertEquals(emptyList<SetupItem>(), state.missing)
        assertFalse(state.needsFirstRun)
    }

    @Test
    fun `nothing granted - everything the remote needs is missing`() {
        val state = SetupState(files = false, home = false, overlay = false, remote = true)
        assertEquals(listOf(SetupItem.FILES, SetupItem.HOME, SetupItem.OVERLAY), state.missing)
        assertTrue(state.needsFirstRun)
    }

    @Test
    fun `Candle not the home screen opens the checklist`() {
        val state = SetupState(files = true, home = false, overlay = true, remote = false)
        assertEquals(listOf(SetupItem.HOME), state.missing)
        assertTrue(state.needsFirstRun)
    }

    @Test
    fun `drawing over apps is only missing when the phone remote is on`() {
        val off = SetupState(files = true, home = true, overlay = false, remote = false)
        assertEquals(emptyList<SetupItem>(), off.missing)
        assertFalse("not granted, so OK still offers to grant it", off.granted(SetupItem.OVERLAY))
        assertEquals(listOf(SetupItem.OVERLAY), off.copy(remote = true).missing)
        assertFalse("the overlay alone never opens the checklist", off.copy(remote = true).needsFirstRun)
    }

    @Test
    fun `on the HY300 - adb for file access, the role dialog for home, the overlay screen`() {
        val hy300 = setOf<SetupWay>(
            SetupWay.HomeRole,
            SetupWay.Screen("android.settings.action.MANAGE_OVERLAY_PERMISSION", forPackage = true),
        )
        assertEquals(
            SetupWay.Adb("adb shell appops set $pkg MANAGE_EXTERNAL_STORAGE allow"),
            Setup.way(SetupItem.FILES, pkg, hy300::contains),
        )
        assertEquals(SetupWay.HomeRole, Setup.way(SetupItem.HOME, pkg, hy300::contains))
        assertEquals(
            SetupWay.Screen("android.settings.action.MANAGE_OVERLAY_PERMISSION", forPackage = true),
            Setup.way(SetupItem.OVERLAY, pkg, hy300::contains),
        )
    }

    @Test
    fun `the app's own file access screen comes before the general one`() {
        assertEquals(
            SetupWay.Screen("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", forPackage = true),
            Setup.way(SetupItem.FILES, pkg) { true },
        )
        assertEquals(
            SetupWay.Screen("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", forPackage = false),
            Setup.way(SetupItem.FILES, pkg) { it != SetupWay.Screen("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", true) },
        )
    }

    @Test
    fun `a device with no screens gets an adb command for every item`() {
        SetupItem.entries.forEach { item ->
            val way = Setup.way(item, pkg) { false }
            assertTrue("$item: $way", way is SetupWay.Adb && pkg in way.command)
        }
        assertEquals(
            SetupWay.Adb("adb shell cmd package set-home-activity $pkg/.MainActivity"),
            Setup.way(SetupItem.HOME, pkg) { false },
        )
    }
}
