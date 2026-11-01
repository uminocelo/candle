package com.uminocelo.candle.player

import com.uminocelo.candle.remote.RemoteApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingTest {
    private fun snapshot(kind: PlaybackKind, playing: Boolean, at: Long, position: Long = 10_000, duration: Long = 60_000) =
        PlaybackSnapshot(kind, kind.name.lowercase(), playing, position, duration, reportedAt = at)

    @Test
    fun `nothing reported is idle`() {
        assertNull(NowPlaying.pick(emptyList()))
        assertEquals("""{"state":"idle"}""", RemoteApi.nowPlayingJson(null, now = 0))
    }

    @Test
    fun `a playing video moves on from its report`() {
        val video = snapshot(PlaybackKind.VIDEO, playing = true, at = 1_000)
        assertEquals(
            """{"state":"playing","kind":"video","title":"video","positionMs":12500,"durationMs":60000}""",
            RemoteApi.nowPlayingJson(video, now = 3_500),
        )
    }

    @Test
    fun `playing music, and a paused player stays where it stopped`() {
        val music = snapshot(PlaybackKind.MUSIC, playing = true, at = 0, position = 0, duration = 180_000)
        assertEquals(
            """{"state":"playing","kind":"music","title":"music","positionMs":4000,"durationMs":180000}""",
            RemoteApi.nowPlayingJson(music, now = 4_000),
        )
        val paused = snapshot(PlaybackKind.VIDEO, playing = false, at = 0)
        assertEquals(
            """{"state":"paused","kind":"video","title":"video","positionMs":10000,"durationMs":60000}""",
            RemoteApi.nowPlayingJson(paused, now = 99_000),
        )
    }

    @Test
    fun `the projected position stops at the end, or keeps going when the length is unknown`() {
        assertEquals(60_000, snapshot(PlaybackKind.VIDEO, true, at = 0).positionAt(now = 120_000))
        assertEquals(130_000, snapshot(PlaybackKind.VIDEO, true, at = 0, duration = 0).positionAt(now = 120_000))
        assertEquals(10_000, snapshot(PlaybackKind.VIDEO, true, at = 5_000).positionAt(now = 1_000))
    }

    @Test
    fun `a playing player wins over a paused one, then the latest report`() {
        val pausedVideo = snapshot(PlaybackKind.VIDEO, playing = false, at = 9_000)
        val playingMusic = snapshot(PlaybackKind.MUSIC, playing = true, at = 1_000)
        assertEquals(playingMusic, NowPlaying.pick(listOf(pausedVideo, playingMusic)))
        val pausedMusic = playingMusic.copy(playing = false)
        assertEquals(pausedVideo, NowPlaying.pick(listOf(pausedMusic, pausedVideo)))
    }
}
