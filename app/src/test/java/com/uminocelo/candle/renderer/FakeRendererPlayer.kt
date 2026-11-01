package com.uminocelo.candle.renderer

import com.uminocelo.candle.player.PlaybackKind
import com.uminocelo.candle.player.PlaybackSnapshot

/**
 * Behaves like the video player as the renderer sees it: [play] opens the URL and
 * reports it playing (unless [reportsOnOpen] is off, like a player still loading).
 */
class FakeRendererPlayer(private val clock: () -> Long) : RendererPlayer {
    val played = mutableListOf<Pair<String, String>>()
    val errors = mutableListOf<Pair<String, String>>()
    val seeks = mutableListOf<Long>()
    var snapshot: PlaybackSnapshot? = null
    var volumePercent = 40
    var mutedNow = false
    var reportsOnOpen = true
    var durationMs = 60_000L

    override fun play(url: String, title: String) {
        played += url to title
        if (reportsOnOpen) report(url, title, playing = true, positionMs = 0)
    }

    fun report(url: String, title: String = "t", playing: Boolean, positionMs: Long) {
        snapshot = PlaybackSnapshot(PlaybackKind.VIDEO, title, playing, positionMs, durationMs, clock(), url)
    }

    override fun resume() = update(playing = true)

    override fun pause() = update(playing = false)

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        snapshot = snapshot?.copy(positionMs = positionMs, reportedAt = clock())
    }

    override fun stop() {
        snapshot = null
    }

    override fun snapshot(): PlaybackSnapshot? = snapshot

    override fun volume() = volumePercent

    override fun setVolume(percent: Int) {
        volumePercent = percent
    }

    override fun isMuted() = mutedNow

    override fun setMuted(muted: Boolean) {
        mutedNow = muted
    }

    override fun showError(title: String, reason: String) {
        errors += title to reason
    }

    private fun update(playing: Boolean) {
        val current = snapshot ?: return
        snapshot = current.copy(playing = playing, playWhenReady = playing, positionMs = current.positionAt(clock()), reportedAt = clock())
    }
}
