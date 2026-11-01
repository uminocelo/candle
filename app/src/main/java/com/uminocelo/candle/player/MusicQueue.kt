package com.uminocelo.candle.player

import androidx.media3.common.Player
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaType
import com.uminocelo.candle.media.sortedForBrowsing
import kotlin.random.Random

object MusicQueue {
    /** The audio files of a folder listing, in browsing order. */
    fun tracksIn(items: List<MediaItem>): List<MediaItem> =
        items.filter { it.type == MediaType.AUDIO }.sortedForBrowsing()

    /**
     * A shuffle order that starts with [first] and then visits every other track once,
     * so turning shuffle on mid-track never skips tracks in that pass.
     */
    fun shuffledIndices(count: Int, first: Int, random: Random): IntArray {
        require(count == 0 || first in 0 until count) { "first=$first outside 0 until $count" }
        if (count == 0) return IntArray(0)
        return intArrayOf(first) + (0 until count).filter { it != first }.shuffled(random)
    }

    /** Off → all → one → off, as the remote cycles it. */
    fun nextRepeatMode(mode: Int): Int = when (mode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
}
