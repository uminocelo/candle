package com.uminocelo.candle.renderer

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.uminocelo.candle.R
import com.uminocelo.candle.player.ActiveVideo
import com.uminocelo.candle.player.NowPlaying
import com.uminocelo.candle.player.PlaybackKind
import com.uminocelo.candle.player.PlaybackSnapshot
import com.uminocelo.candle.player.PlaybackTarget
import com.uminocelo.candle.player.VideoPlayerActivity
import kotlin.math.roundToInt

/**
 * Drives the video player and the projector's volume for phone apps. Opening a video
 * from the background relies on the SYSTEM_ALERT_WINDOW app op.
 */
class AndroidRendererPlayer(private val context: Context) : RendererPlayer {
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun play(url: String, title: String) {
        context.startActivity(
            VideoPlayerActivity.intent(context, PlaybackTarget.cast(url, title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun resume() {
        ActiveVideo.commands?.play()
    }

    override fun pause() {
        ActiveVideo.commands?.pause()
    }

    override fun seekTo(positionMs: Long) {
        ActiveVideo.commands?.seekTo(positionMs)
    }

    override fun stop() {
        ActiveVideo.commands?.close()
    }

    override fun snapshot(): PlaybackSnapshot? = NowPlaying.of(PlaybackKind.VIDEO)

    // The same music volume the remote's keys and the phone page change.
    override fun volume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    override fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
    }

    override fun isMuted(): Boolean = audio.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun setMuted(muted: Boolean) {
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    override fun showError(title: String, reason: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, context.getString(R.string.renderer_error, title, reason), Toast.LENGTH_LONG).show()
        }
    }
}
