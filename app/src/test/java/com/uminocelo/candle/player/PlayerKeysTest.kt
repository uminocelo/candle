package com.uminocelo.candle.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKeysTest {
    @Test
    fun `video keys map to player actions`() {
        assertEquals(VideoAction.PLAY_PAUSE, PlayerKeys.videoAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(VideoAction.PLAY_PAUSE, PlayerKeys.videoAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(VideoAction.SEEK_BACK, PlayerKeys.videoAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(VideoAction.SEEK_FORWARD, PlayerKeys.videoAction(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(VideoAction.TRACKS, PlayerKeys.videoAction(KeyEvent.KEYCODE_MENU))
        assertEquals(VideoAction.SHOW_CONTROLS, PlayerKeys.videoAction(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `music keys map to queue actions`() {
        assertEquals(MusicAction.PLAY_PAUSE, PlayerKeys.musicAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(MusicAction.PREVIOUS, PlayerKeys.musicAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(MusicAction.NEXT, PlayerKeys.musicAction(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(MusicAction.TOGGLE_SHUFFLE, PlayerKeys.musicAction(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(MusicAction.CYCLE_REPEAT, PlayerKeys.musicAction(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `unknown keys and Back are left to the system`() {
        assertNull(PlayerKeys.videoAction(KeyEvent.KEYCODE_BACK))
        assertNull(PlayerKeys.videoAction(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(PlayerKeys.videoAction(KeyEvent.KEYCODE_A))
        assertNull(PlayerKeys.musicAction(KeyEvent.KEYCODE_BACK))
        assertNull(PlayerKeys.musicAction(KeyEvent.KEYCODE_MENU))
    }

    @Test
    fun `seeking moves by the step and stays inside the file`() {
        assertEquals(40_000L, PlayerKeys.seekTarget(30_000, PlayerKeys.SEEK_STEP_MS, 60_000))
        assertEquals(20_000L, PlayerKeys.seekTarget(30_000, -PlayerKeys.SEEK_STEP_MS, 60_000))
        assertEquals(0L, PlayerKeys.seekTarget(4_000, -PlayerKeys.SEEK_STEP_MS, 60_000))
        assertEquals(60_000L, PlayerKeys.seekTarget(55_000, PlayerKeys.SEEK_STEP_MS, 60_000))
    }

    @Test
    fun `seeking with an unknown duration only clamps at zero`() {
        assertEquals(1_010_000L, PlayerKeys.seekTarget(1_000_000, PlayerKeys.SEEK_STEP_MS, -9_223_372_036_854_775_807))
        assertEquals(0L, PlayerKeys.seekTarget(5_000, -PlayerKeys.SEEK_STEP_MS, 0))
    }

    @Test
    fun `a short OK plays or pauses when let go`() {
        val ok = OkPress(500)
        assertEquals(null, ok.down(0, 0))
        assertEquals(VideoAction.PLAY_PAUSE, ok.up(canceled = false))
    }

    @Test
    fun `holding OK opens the tracks once and the release does nothing`() {
        val ok = OkPress(500)
        assertEquals(null, ok.down(0, 0))
        assertEquals("repeats before the hold time do nothing", null, ok.down(1, 300))
        assertEquals(VideoAction.TRACKS, ok.down(2, 550))
        assertEquals(null, ok.down(3, 600))
        assertEquals(null, ok.up(canceled = false))
        assertEquals("the next press starts fresh", null, ok.down(0, 0))
        assertEquals(VideoAction.PLAY_PAUSE, ok.up(canceled = false))
    }

    @Test
    fun `Android's long-press flag counts even with no time held`() {
        val ok = OkPress(500)
        ok.down(0, 0)
        assertEquals(VideoAction.TRACKS, ok.down(1, 0, longPress = true))
        assertEquals(null, ok.up(canceled = false))
    }

    @Test
    fun `a canceled OK does nothing`() {
        val ok = OkPress(500)
        ok.down(0, 0)
        assertEquals(null, ok.up(canceled = true))
    }
}
