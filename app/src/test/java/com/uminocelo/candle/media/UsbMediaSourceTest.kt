package com.uminocelo.candle.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** A temporary folder stands in for a drive mounted at /storage/<uuid>. */
class UsbMediaSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var drive: UsbDrive
    private lateinit var source: UsbMediaSource

    @Before
    fun setUp() {
        drive = UsbDrive(uuid = "1234-ABCD", label = "Films", mountPath = tmp.root.path)
        source = UsbMediaSource(drive)
    }

    @Test
    fun `lists the drive root with folders first`() {
        tmp.newFolder("Series")
        tmp.newFile("movie.mkv").writeBytes(ByteArray(5))

        val items = source.list(MediaSource.ROOT).sortedForBrowsing()
        assertEquals(listOf("Series", "movie.mkv"), items.map { it.name })
        assertEquals(MediaType.VIDEO, items[1].type)
        assertEquals(5L, items[1].size)
    }

    @Test
    fun `lists subfolders by the paths it returned`() {
        tmp.newFolder("Series", "Season 1")
        tmp.newFile("Series/Season 1/ep1.mp4")
        val series = source.list(MediaSource.ROOT).single()
        val season = source.list(series.path).single()

        assertEquals(listOf("ep1.mp4"), source.list(season.path).map { it.name })
    }

    @Test
    fun `skips hidden files and the folders Windows and Android leave on drives`() {
        tmp.newFile(".DS_Store")
        tmp.newFile("._movie.mkv")
        tmp.newFolder(".Trashes")
        tmp.newFolder("System Volume Information")
        tmp.newFolder("\$RECYCLE.BIN")
        tmp.newFolder("LOST.DIR")
        tmp.newFolder("Android")
        tmp.newFile("movie.mkv")

        assertEquals(listOf("movie.mkv"), source.list(MediaSource.ROOT).map { it.name })
    }

    @Test
    fun `an unreadable folder is reported as an IO error and does not break its parent`() {
        val locked = tmp.newFolder("Locked")
        tmp.newFile("Locked/secret.mkv")
        assumeFalse("root can read anything", System.getProperty("user.name") == "root")
        assertTrue(locked.setReadable(false))
        try {
            assertEquals(listOf("Locked"), source.list(MediaSource.ROOT).map { it.name })
            assertThrows(IOException::class.java) { source.list(locked.path) }
        } finally {
            locked.setReadable(true)
        }
    }

    @Test
    fun `a folder that vanished with the drive is an IO error`() {
        assertThrows(IOException::class.java) { source.list(File(tmp.root, "gone").path) }
    }

    @Test
    fun `opens files as file URIs, so playback treats them as local files`() {
        val movie = tmp.newFile("movie.mkv")
        val item = source.list(MediaSource.ROOT).single()

        assertEquals(MediaStream.Location(movie.toURI().toString()), source.open(item))
    }

    @Test
    fun `id and name come from the drive`() {
        assertEquals("usb:1234-ABCD", source.id)
        assertEquals("Films", source.displayName)
        assertEquals("usb:${tmp.root.path}", UsbMediaSource(drive.copy(uuid = null)).id)
    }

    @Test
    fun `contains matches the mount point and below, not sibling mounts`() {
        val mount = UsbDrive("A", "A", "/storage/AB12")
        assertTrue(mount.contains("/storage/AB12"))
        assertTrue(mount.contains("/storage/AB12/Films/a.mkv"))
        assertFalse(mount.contains("/storage/AB123/a.mkv"))
        assertFalse(mount.contains("/storage/emulated/0/a.mkv"))
        assertTrue(UsbDrive("A", "A", "/storage/AB12/").contains("/storage/AB12/a.mkv"))
    }

    @Test
    fun `breadcrumb shows the label and the path inside the drive`() {
        val mount = UsbDrive("A", "Films", "/storage/AB12")
        assertEquals("Films", mount.breadcrumb(MediaSource.ROOT))
        assertEquals("Films/Kids/2024", mount.breadcrumb("/storage/AB12/Kids/2024"))
    }
}
