package com.uminocelo.candle.settings

import com.uminocelo.candle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory settings; tables in [locked] refuse writes, like a missing grant. */
private class FakeSettingsStore(
    val values: MutableMap<Pair<Table, String>, String> = mutableMapOf(),
    var locked: Set<Table> = emptySet(),
) : SettingsStore {
    override fun get(table: Table, key: String): String? = values[table to key]

    override fun put(table: Table, key: String, value: String): Boolean {
        if (table in locked) return false
        values[table to key] = value
        return true
    }
}

class DirectSettingsTest {
    @Test
    fun `reads the current value, with the default for a key never written`() {
        val store = FakeSettingsStore(mutableMapOf((Table.GLOBAL to "adb_enabled") to "1"))
        assertEquals(R.string.choice_on, DirectSettings.NETWORK_DEBUGGING.current(store)?.label)
        assertEquals(R.string.choice_on, DirectSettings.ANIMATIONS.current(store)?.label)
        assertEquals(R.string.duration_never, DirectSettings.SLEEP.current(store)?.label)
    }

    @Test
    fun `animation scales match however they were written`() {
        val store = FakeSettingsStore()
        for (written in listOf("0", "0.0")) {
            store.values[Table.GLOBAL to "window_animation_scale"] = written
            assertEquals(written, R.string.choice_off, DirectSettings.ANIMATIONS.current(store)?.label)
        }
        store.values[Table.GLOBAL to "window_animation_scale"] = "1.0"
        assertEquals(R.string.choice_on, DirectSettings.ANIMATIONS.current(store)?.label)
    }

    @Test
    fun `a value Candle does not offer is custom, and the next choice is the first`() {
        val store = FakeSettingsStore(mutableMapOf((Table.SYSTEM to "screen_off_timeout") to "123456"))
        assertNull(DirectSettings.SCREENSAVER.current(store))
        assertEquals(DirectSettings.SCREENSAVER.choices.first(), DirectSettings.SCREENSAVER.next(store))
    }

    @Test
    fun `next cycles through the choices and wraps around`() {
        val store = FakeSettingsStore()
        val setting = DirectSettings.SCREENSAVER
        val seen = (1..setting.choices.size).map {
            setting.next(store).also { setting.apply(store, it) }
        }
        assertEquals(setting.choices, seen)
    }

    @Test
    fun `apply round-trips and writes every key`() {
        val store = FakeSettingsStore()
        val off = DirectSettings.ANIMATIONS.choices.first { it.value == "0" }

        assertTrue(DirectSettings.ANIMATIONS.apply(store, off))
        assertEquals(off, DirectSettings.ANIMATIONS.current(store))
        DirectSettings.ANIMATIONS.keys.forEach { assertEquals(it, "0", store.values[Table.GLOBAL to it]) }
    }

    @Test
    fun `apply fails and changes nothing when the table is locked`() {
        val store = FakeSettingsStore(locked = setOf(Table.SECURE))
        val oneHour = DirectSettings.SLEEP.choices.first { it.value == "3600000" }

        assertFalse(DirectSettings.SLEEP.apply(store, oneHour))
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `only turning network debugging off asks first`() {
        val adb = DirectSettings.NETWORK_DEBUGGING
        assertTrue(adb.needsConfirmation(adb.choices.first { it.value == "0" }))
        assertFalse(adb.needsConfirmation(adb.choices.first { it.value == "1" }))
        assertFalse(DirectSettings.ANIMATIONS.needsConfirmation(DirectSettings.ANIMATIONS.choices.first()))
    }

    @Test
    fun `grants decide which tables can be written`() {
        val full = Grants(secureSettings = true, systemSettings = true)
        assertTrue(full.complete)
        assertTrue(Table.entries.all(full::allows))

        val noSecure = Grants(secureSettings = false, systemSettings = true)
        assertFalse(noSecure.complete)
        assertTrue(noSecure.allows(Table.SYSTEM))
        assertFalse(noSecure.allows(Table.SECURE))
        assertFalse(noSecure.allows(Table.GLOBAL))

        val noSystem = Grants(secureSettings = true, systemSettings = false)
        assertFalse(noSystem.allows(Table.SYSTEM))
        assertTrue(noSystem.allows(Table.GLOBAL))
    }
}

class SystemScreensTest {
    private fun action(name: String) = ScreenTarget.Action(name)

    @Test
    fun `screens that do not resolve are hidden`() {
        val available = SystemScreens.available { it != action("android.settings.WIFI_SETTINGS") }
        assertFalse(available.any { (screen, _) -> screen.title == R.string.screen_wifi })
        assertEquals(SystemScreens.ALL.size - 1, available.size)
    }

    @Test
    fun `the first action that resolves wins`() {
        // The HY300 has no standard Bluetooth screen, only HTC's.
        val onDevice = setOf(action("action.htcsettings.bluetooth"), action("android.settings.BLUETOOTH_SETTINGS"))
        val both = SystemScreens.available { it in onDevice }.toMap()
        assertEquals(action("android.settings.BLUETOOTH_SETTINGS"), both.values.single())

        val htcOnly = SystemScreens.available { it == action("action.htcsettings.bluetooth") }
        assertEquals(listOf(R.string.screen_bluetooth), htcOnly.map { it.first.title })
        assertEquals(action("action.htcsettings.bluetooth"), htcOnly.single().second)
    }

    @Test
    fun `vendor screens are opened by component`() {
        val picture = SystemScreens.ALL.single { it.title == R.string.screen_picture }.targets.single()
        assertTrue(picture is ScreenTarget.Component)
        assertEquals(SystemScreens.TV_SETTINGS_PACKAGE, (picture as ScreenTarget.Component).component.packageName)
    }

    @Test
    fun `nothing resolves, nothing shows`() {
        assertTrue(SystemScreens.available { false }.isEmpty())
    }
}

class MemInfoTest {
    @Test
    fun `parses the fields the panel shows`() {
        val info = MemInfo.parse(
            """
            MemTotal:         952756 kB
            MemFree:           43120 kB
            MemAvailable:     412544 kB
            SwapTotal:        860156 kB
            SwapFree:         740000 kB
            """.trimIndent(),
        )
        assertEquals(MemInfo(952756, 412544, 860156, 740000), info)
        assertEquals(120156L, info.swapUsedKb)
    }

    @Test
    fun `malformed and missing lines become null instead of failing`() {
        val info = MemInfo.parse("MemTotal: lots kB\nMemAvailable:\ngarbage\nSwapTotal: -5 kB\nSwapFree: 10 kB\n")
        assertEquals(MemInfo(null, null, null, 10), info)
        assertNull(info.swapUsedKb)
        assertEquals(MemInfo(null, null, null, null), MemInfo.parse(""))
    }

    @Test
    fun `formats sizes in binary megabytes and gigabytes`() {
        assertEquals("402 MB", formatKb(412544))
        assertEquals("0 MB", formatKb(0))
        assertEquals("1.0 GB", formatKb(1024 * 1024))
        assertEquals("2.9 GB", formatKb(3_040_000))
    }
}
