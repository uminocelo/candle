package com.uminocelo.candle.usb

import android.content.Intent
import android.os.Environment
import com.uminocelo.candle.browse.browsable
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.UsbDrive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbVolumesTest {
    private fun volume(
        uuid: String? = "AB12-CD34",
        description: String? = "SanDisk USB drive",
        state: String = Environment.MEDIA_MOUNTED,
        removable: Boolean = true,
        primary: Boolean = false,
        directory: String? = "/storage/$uuid",
    ) = VolumeInfo(removable, primary, state, uuid, description, directory)

    @Test
    fun `keeps mounted removable drives and drops internal storage`() {
        val drives = UsbVolumes.drives(
            listOf(
                volume(uuid = null, description = "Internal shared storage", removable = false, primary = true, directory = "/storage/emulated/0"),
                volume(),
            ),
        )
        assertEquals(listOf(UsbDrive("AB12-CD34", "SanDisk USB drive", "/storage/AB12-CD34")), drives)
    }

    @Test
    fun `drops drives that are not mounted or have no directory`() {
        val drives = UsbVolumes.drives(
            listOf(
                volume(state = Environment.MEDIA_UNMOUNTED),
                volume(state = Environment.MEDIA_UNMOUNTABLE),
                volume(state = Environment.MEDIA_CHECKING),
                volume(directory = null),
            ),
        )
        assertEquals(emptyList<UsbDrive>(), drives)
    }

    @Test
    fun `marks read-only drives`() {
        val drive = UsbVolumes.drives(listOf(volume(state = Environment.MEDIA_MOUNTED_READ_ONLY))).single()
        assertTrue(drive.readOnly)
    }

    @Test
    fun `labels fall back to the uuid, then the folder name, and sort naturally`() {
        val drives = UsbVolumes.drives(
            listOf(
                volume(uuid = "Z9", description = "Drive 10"),
                volume(uuid = "Y8", description = " "),
                volume(uuid = null, description = null, directory = "/storage/usb0"),
                volume(uuid = "X7", description = "Drive 2"),
            ),
        )
        assertEquals(listOf("Drive 2", "Drive 10", "usb0", "Y8"), drives.map { it.label })
    }

    @Test
    fun `turns storage broadcasts into drive events`() {
        assertEquals(DriveEvent("/storage/AB12", true), UsbVolumes.eventFor(Intent.ACTION_MEDIA_MOUNTED, "/storage/AB12"))
        for (action in listOf(
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
            Intent.ACTION_MEDIA_REMOVED,
        )) {
            assertEquals(action, DriveEvent("/storage/AB12", false), UsbVolumes.eventFor(action, "/storage/AB12"))
        }
        assertNull(UsbVolumes.eventFor(Intent.ACTION_MEDIA_SCANNER_FINISHED, "/storage/AB12"))
        assertNull(UsbVolumes.eventFor(Intent.ACTION_MEDIA_MOUNTED, null))
    }

    @Test
    fun `a removal affects files on that drive only`() {
        val event = DriveEvent("/storage/AB12", mounted = false)
        assertTrue(event.affects("/storage/AB12/Films/a.mkv"))
        assertFalse(event.affects("/storage/AB123/a.mkv"))
        assertFalse(event.affects("/storage/emulated/0/Movies/a.mkv"))
    }

    @Test
    fun `the browser shows folders, videos and music only`() {
        val items = listOf(
            MediaItem.file("notes.txt", "/d/notes.txt"),
            MediaItem.file("film.srt", "/d/film.srt"),
            MediaItem.file("film.mkv", "/d/film.mkv"),
            MediaItem.file("song.flac", "/d/song.flac"),
            MediaItem.folder("Series", "/d/Series"),
        )
        assertEquals(listOf("Series", "film.mkv", "song.flac"), items.browsable().map { it.name })
    }
}
