package com.uminocelo.candle.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunTest {
    @Test
    fun `both granted - the card hides`() {
        val state = FirstRun(filesAccess = true, batteryExempt = true)
        assertFalse(state.visible)
        assertFalse(state.showFilesAccess)
        assertFalse(state.showBattery)
    }

    @Test
    fun `only one granted - the card shows just the other`() {
        val noBattery = FirstRun(filesAccess = true, batteryExempt = false)
        assertTrue(noBattery.visible)
        assertFalse(noBattery.showFilesAccess)
        assertTrue(noBattery.showBattery)

        val noFiles = FirstRun(filesAccess = false, batteryExempt = true)
        assertTrue(noFiles.visible)
        assertTrue(noFiles.showFilesAccess)
        assertFalse(noFiles.showBattery)
    }

    @Test
    fun `neither granted - both prompts show`() {
        val state = FirstRun(filesAccess = false, batteryExempt = false)
        assertTrue(state.visible && state.showFilesAccess && state.showBattery)
    }

    @Test
    fun `the app's own file access screen is tried before the list of all apps`() {
        assertEquals(
            listOf(
                "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" to true,
                "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION" to false,
            ),
            FirstRun.FILES_ACCESS_ACTIONS,
        )
    }
}
