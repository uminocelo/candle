package com.uminocelo.candle.player

import com.uminocelo.candle.resume.ResumeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private val target = PlaybackTarget.localFile("/sdcard/Movies/Film.mkv")
    private val hour = 3_600_000L

    @Test
    fun `saves the position partway through`() {
        val entry = ResumePolicy.entryFor(target, 754_000, hour, now = 42)
        assertEquals(ResumeEntry("file", "/sdcard/Movies/Film.mkv", "Film", 754_000, hour, 42), entry)
    }

    @Test
    fun `never keeps a video sent from a phone`() {
        assertNull(ResumePolicy.entryFor(PlaybackTarget.cast("http://phone:8080/v.mp4", "V"), 754_000, hour, now = 0))
    }

    @Test
    fun `clears the position once 95 percent is watched`() {
        assertNull(ResumePolicy.entryFor(target, (hour * 0.95).toLong(), hour, now = 0))
        assertNull(ResumePolicy.entryFor(target, hour, hour, now = 0))
        assertTrue(ResumePolicy.entryFor(target, (hour * 0.95).toLong() - 1, hour, now = 0) != null)
    }

    @Test
    fun `does not save the first seconds`() {
        assertNull(ResumePolicy.entryFor(target, ResumePolicy.MIN_RESUME_MS - 1, hour, now = 0))
    }

    @Test
    fun `unknown duration still saves and stores zero`() {
        assertEquals(0L, ResumePolicy.entryFor(target, 60_000, -1, now = 0)?.durationMs)
    }

    @Test
    fun `offers resume only for real progress`() {
        fun entry(position: Long, duration: Long) = ResumeEntry("file", "/a.mkv", "A", position, duration, 0)
        assertFalse(ResumePolicy.shouldOffer(null))
        assertFalse(ResumePolicy.shouldOffer(entry(5_000, hour)))
        assertFalse(ResumePolicy.shouldOffer(entry(hour, hour)))
        assertTrue(ResumePolicy.shouldOffer(entry(754_000, hour)))
    }

    @Test
    fun `continue watching entries map back to playable targets`() {
        val local = ResumeEntry("file", "/sdcard/Movies/Big Buck Bunny.mkv", "Bunny", 20_000, hour, 0)
        assertEquals("file:/sdcard/Movies/Big%20Buck%20Bunny.mkv", PlaybackTarget.forEntry(local)?.uri)
        assertEquals("Bunny", PlaybackTarget.forEntry(local)?.title)

        val content = ResumeEntry("uri", "content://media/1", "Clip", 20_000, hour, 0)
        assertEquals("content://media/1", PlaybackTarget.forEntry(content)?.uri)

        assertNull(PlaybackTarget.forEntry(local.copy(sourceId = "ftp:server")))
    }
}
