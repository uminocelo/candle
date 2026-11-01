package com.uminocelo.candle.resume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResumeTest {
    private fun entry(path: String, updatedAt: Long, position: Long = 0) =
        ResumeEntry("usb", path, path.removePrefix("/"), position, 60_000, updatedAt)

    @Test
    fun `codec round-trips an entry`() {
        val e = ResumeEntry("smb:nas", "/Movies/A b.mkv", "A b", 12_345, 5_400_000, 1_700_000_000_000)
        assertEquals(e, ResumeCodec.decode(ResumeCodec.encode(e)))
    }

    @Test
    fun `codec strips the separator from titles and rejects it in paths`() {
        val e = ResumeEntry("usb", "/a.mkv", "A\u001FB", 0, 0, 0)
        assertEquals("A B", ResumeCodec.decode(ResumeCodec.encode(e))?.title)
        assertThrows(IllegalArgumentException::class.java) {
            ResumeCodec.encode(e.copy(path = "/a\u001Fb.mkv"))
        }
    }

    @Test
    fun `codec rejects malformed lines`() {
        assertNull(ResumeCodec.decode(""))
        assertNull(ResumeCodec.decode("usb\u001F/a.mkv\u001FA"))
        assertNull(ResumeCodec.decode("usb\u001F/a.mkv\u001FA\u001Fx\u001F0\u001F0"))
    }

    @Test
    fun `upsert replaces the same file and keeps newest first`() {
        val list = listOf(entry("/b.mkv", 2), entry("/a.mkv", 1))
        val updated = list.upsert(entry("/a.mkv", 3, position = 500), limit = 20)
        assertEquals(listOf("/a.mkv", "/b.mkv"), updated.map { it.path })
        assertEquals(500L, updated.first().positionMs)
    }

    @Test
    fun `upsert drops the oldest beyond the limit`() {
        val list = listOf(entry("/c.mkv", 3), entry("/b.mkv", 2), entry("/a.mkv", 1))
        assertEquals(
            listOf("/d.mkv", "/c.mkv"),
            list.upsert(entry("/d.mkv", 4), limit = 2).map { it.path },
        )
    }

    @Test
    fun `durations format as m-ss or h-mm-ss`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-5_000))
        assertEquals("4:05", formatDuration(245_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }
}
