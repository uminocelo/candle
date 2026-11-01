package com.uminocelo.candle.home

import com.uminocelo.candle.resume.ResumeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContentTest {
    private class FakeCatalog(
        private val apps: List<LaunchableApp>,
        private val installed: Set<ComponentRef>,
    ) : AppCatalog {
        override fun tvApps() = apps
        override fun isInstalled(component: ComponentRef) = component in installed
    }

    private fun app(pkg: String, label: String, cls: String = "$pkg.Main") =
        LaunchableApp(ComponentRef(pkg, cls), label)

    private val youtube = app("com.google.android.youtube.tv", "YouTube")
    private val store = app("com.android.vending", "Google Play Store")
    private val self = "com.uminocelo.candle"

    @Test
    fun `HDMI shortcut points at the AwSource input screen`() {
        assertEquals("com.softwinner.awsource", Shortcuts.HDMI.packageName)
        assertEquals("com.softwinner.awsource.MainActivity", Shortcuts.HDMI.className)
    }

    @Test
    fun `all four source tiles show when HDMI is installed`() {
        val home = buildHome(FakeCatalog(emptyList(), setOf(Shortcuts.HDMI)), emptyList(), self)
        assertEquals(
            listOf(SourceTile.USB, SourceTile.NETWORK, SourceTile.HDMI, SourceTile.SETTINGS),
            home.sources,
        )
    }

    @Test
    fun `HDMI tile is hidden when its package is missing`() {
        val home = buildHome(FakeCatalog(emptyList(), emptySet()), emptyList(), self)
        assertFalse(SourceTile.HDMI in home.sources)
        assertEquals(listOf(SourceTile.USB, SourceTile.NETWORK, SourceTile.SETTINGS), home.sources)
    }

    @Test
    fun `apps row lists only what the catalog reports, sorted by label`() {
        val home = buildHome(FakeCatalog(listOf(youtube, store), emptySet()), emptyList(), self)
        assertEquals(listOf("Google Play Store", "YouTube"), home.apps.map { it.label })
    }

    @Test
    fun `apps row is empty when no TV apps are installed`() {
        val home = buildHome(FakeCatalog(emptyList(), emptySet()), emptyList(), self)
        assertTrue(home.apps.isEmpty())
    }

    @Test
    fun `apps row leaves out Candle, TV settings and duplicates`() {
        val apps = listOf(
            youtube,
            youtube,
            app(self, "Candle", "$self.MainActivity"),
            app(Shortcuts.TV_SETTINGS_PACKAGE, "Settings"),
        )
        val home = buildHome(FakeCatalog(apps, emptySet()), emptyList(), self)
        assertEquals(listOf(youtube), home.apps)
    }

    @Test
    fun `continue watching shows only when there are entries`() {
        val catalog = FakeCatalog(emptyList(), emptySet())
        assertFalse(buildHome(catalog, emptyList(), self).showContinueWatching)

        val entry = ResumeEntry("usb", "/a.mkv", "A", 1_000, 10_000, 1)
        val home = buildHome(catalog, listOf(entry), self)
        assertTrue(home.showContinueWatching)
        assertEquals(listOf(entry), home.continueWatching)
    }
}
