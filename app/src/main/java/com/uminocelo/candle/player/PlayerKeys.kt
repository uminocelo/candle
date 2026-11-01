package com.uminocelo.candle.player

import android.view.KeyEvent

enum class VideoAction { PLAY_PAUSE, PLAY, PAUSE, SEEK_BACK, SEEK_FORWARD, TRACKS, SHOW_CONTROLS }

enum class MusicAction { PLAY_PAUSE, PREVIOUS, NEXT, TOGGLE_SHUFFLE, CYCLE_REPEAT }

enum class PhotoAction { PREVIOUS, NEXT, TOGGLE_SLIDESHOW, SHOW_CAPTION }

enum class ReaderAction { UP, DOWN, LEFT, RIGHT, NEXT_PAGE, PREVIOUS_PAGE, ZOOM }

/** Remote-control key mapping. Keys that map to nothing fall through to the default handling (e.g. Back). */
object PlayerKeys {
    const val SEEK_STEP_MS = 10_000L

    fun videoAction(keyCode: Int): VideoAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> VideoAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> VideoAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> VideoAction.PAUSE
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> VideoAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> VideoAction.SEEK_FORWARD
        KeyEvent.KEYCODE_MENU -> VideoAction.TRACKS
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> VideoAction.SHOW_CONTROLS
        else -> null
    }

    fun musicAction(keyCode: Int): MusicAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MusicAction.PLAY_PAUSE
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MusicAction.PREVIOUS
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> MusicAction.NEXT
        KeyEvent.KEYCODE_DPAD_UP -> MusicAction.TOGGLE_SHUFFLE
        KeyEvent.KEYCODE_DPAD_DOWN -> MusicAction.CYCLE_REPEAT
        else -> null
    }

    fun photoAction(keyCode: Int): PhotoAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> PhotoAction.PREVIOUS
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PhotoAction.NEXT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PhotoAction.TOGGLE_SLIDESHOW
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> PhotoAction.SHOW_CAPTION
        else -> null
    }

    fun readerAction(keyCode: Int): ReaderAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> ReaderAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> ReaderAction.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> ReaderAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> ReaderAction.RIGHT
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> ReaderAction.NEXT_PAGE
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> ReaderAction.PREVIOUS_PAGE
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> ReaderAction.ZOOM
        else -> null
    }

    fun isOk(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    /** Position after seeking by [deltaMs], kept inside the file. An unknown duration (<= 0) only clamps at 0. */
    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0)
        return if (durationMs > 0) target.coerceAtMost(durationMs) else target
    }
}

/**
 * OK in the video player: a short press plays or pauses when it is let go; holding it for
 * [longPressMs] opens the track menu instead, for remotes with no Menu key. Either Android's
 * long-press flag or the time held counts, since IR remotes may repeat keys without the flag.
 */
class OkPress(private val longPressMs: Long) {
    private var held = false

    /** A key-down; [repeatCount] 0 starts a press, [heldMs] is how long the key has been down. */
    fun down(repeatCount: Int, heldMs: Long, longPress: Boolean = false): VideoAction? {
        if (repeatCount == 0) {
            held = false
            return null
        }
        if (held || !longPress && heldMs < longPressMs) return null
        held = true
        return VideoAction.TRACKS
    }

    /** The key-up; [canceled] when the system took the press away. */
    fun up(canceled: Boolean): VideoAction? = if (held || canceled) null else VideoAction.PLAY_PAUSE
}
