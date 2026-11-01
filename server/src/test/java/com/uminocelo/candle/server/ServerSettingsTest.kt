package com.uminocelo.candle.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServerSettingsTest {
    private val root: File = Files.createTempDirectory("storage").toFile().apply {
        deleteOnExit()
        listOf("Movies", "Music", "Download", "Android", ".thumbnails", "Podcasts").forEach { File(this, it).mkdir() }
    }
    private val store = MapStore()
    private val settings = ServerSettings(store, root, "Living Room Phone")

    @Test
    fun `defaults - on, the phone's name, the usual media folders`() {
        assertTrue(settings.enabled)
        assertEquals("Living Room Phone", settings.name)
        assertEquals(listOf("DCIM", "Documents", "Download", "Movies", "Music", "Pictures"), settings.sharedNames)
        assertEquals("only the folders that exist are served", listOf("Download", "Movies", "Music"), settings.sharedFolders().map { it.name })
    }

    @Test
    fun `the default name is the phone's name, else its model, else Candle Server`() {
        assertEquals("Living Room Phone", ServerSettings.defaultName(" Living Room Phone ", "Pixel 8"))
        assertEquals("Pixel 8", ServerSettings.defaultName(null, "Pixel 8"))
        assertEquals("Pixel 8", ServerSettings.defaultName("  ", "Pixel 8"))
        assertEquals("Candle Server", ServerSettings.defaultName(null, null))
        assertEquals("Candle Server", ServerSettings.defaultName("", " "))
    }

    @Test
    fun `the name is trimmed and blank goes back to the default`() {
        settings.name = "  Living room  "
        assertEquals("Living room", settings.name)
        settings.name = "   "
        assertEquals("Living Room Phone", settings.name)
    }

    @Test
    fun `sharing and unsharing a folder applies at once`() {
        settings.share("Podcasts")
        assertTrue("Podcasts" in settings.sharedFolders().map { it.name })
        settings.unshare("Movies")
        assertFalse("Movies" in settings.sharedFolders().map { it.name })
        // A fresh instance over the same store sees the same thing: nothing is cached.
        assertEquals(settings.sharedNames, ServerSettings(store, root, "x").sharedNames)
    }

    @Test
    fun `the folder list shows every visible folder, and shared ones that are gone`() {
        val all = settings.allFolders().associateBy { it.name }
        assertEquals("hidden folders and apps' data are left out", setOf("DCIM", "Documents", "Download", "Movies", "Music", "Pictures", "Podcasts"), all.keys)
        assertFalse(all.getValue("Podcasts").shared)
        assertTrue(all.getValue("DCIM").shared)
        assertFalse("a shared folder that does not exist is flagged", all.getValue("DCIM").exists)
    }

    @Test
    fun `a deleted folder stays shared but is no longer served`() {
        File(root, "Music").deleteRecursively()
        assertTrue("Music" in settings.sharedNames)
        assertFalse("Music" in settings.sharedFolders().map { it.name })
        File(root, "Music").mkdir()
        assertTrue("Music" in settings.sharedFolders().map { it.name })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `only top-level folder names can be shared`() {
        settings.share("../etc")
    }

    @Test
    fun `sharing with players is on by default and the UPnP id is made once`() {
        assertTrue(settings.shareWithPlayers)
        settings.shareWithPlayers = false
        assertFalse(ServerSettings(store, root, "x").shareWithPlayers)
        val uuid = settings.uuid
        assertEquals(36, uuid.length)
        assertEquals(uuid, ServerSettings(store, root, "x").uuid)
    }

    @Test
    fun `turning the server off is remembered`() {
        settings.enabled = false
        assertFalse(ServerSettings(store, root, "x").enabled)
    }
}
