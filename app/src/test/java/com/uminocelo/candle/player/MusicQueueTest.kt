package com.uminocelo.candle.player

import androidx.media3.common.Player
import com.uminocelo.candle.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class MusicQueueTest {
    @Test
    fun `queue keeps only audio files in natural order`() {
        val items = listOf(
            MediaItem.file("10 Ten.mp3", "/m/10 Ten.mp3"),
            MediaItem.file("cover.jpg", "/m/cover.jpg"),
            MediaItem.folder("Extras", "/m/Extras"),
            MediaItem.file("2 Two.flac", "/m/2 Two.flac"),
            MediaItem.file("1 One.mp3", "/m/1 One.mp3"),
        )
        assertEquals(listOf("1 One.mp3", "2 Two.flac", "10 Ten.mp3"), MusicQueue.tracksIn(items).map { it.name })
    }

    @Test
    fun `shuffle starts with the current track and plays each track once per pass`() {
        repeat(50) { seed ->
            val order = MusicQueue.shuffledIndices(count = 8, first = 3, random = Random(seed))
            assertEquals(3, order.first())
            assertEquals((0 until 8).toList(), order.sorted())
        }
    }

    @Test
    fun `shuffle of one track is that track, and of none is empty`() {
        assertEquals(listOf(0), MusicQueue.shuffledIndices(1, 0, Random(1)).toList())
        assertEquals(emptyList<Int>(), MusicQueue.shuffledIndices(0, 0, Random(1)).toList())
        assertThrows(IllegalArgumentException::class.java) { MusicQueue.shuffledIndices(3, 3, Random(1)) }
    }

    @Test
    fun `repeat cycles off, all, single track, off`() {
        assertEquals(Player.REPEAT_MODE_ALL, MusicQueue.nextRepeatMode(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, MusicQueue.nextRepeatMode(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, MusicQueue.nextRepeatMode(Player.REPEAT_MODE_ONE))
    }
}
