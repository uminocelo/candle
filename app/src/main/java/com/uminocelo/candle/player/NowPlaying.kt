package com.uminocelo.candle.player

import android.os.SystemClock
import androidx.media3.common.Player
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class PlaybackKind { VIDEO, MUSIC }

/**
 * What a player reported at [reportedAt] (elapsed-realtime ms). Durations are ms; 0 means unknown.
 * [playWhenReady] stays true while a playing video buffers after a seek, when [playing] is false.
 */
data class PlaybackSnapshot(
    val kind: PlaybackKind,
    val title: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val reportedAt: Long,
    val uri: String = "",
    val playWhenReady: Boolean = playing,
) {
    /** The position at [now]: a playing player has moved on since it reported. */
    fun positionAt(now: Long): Long {
        if (!playing) return positionMs
        val moved = positionMs + (now - reportedAt).coerceAtLeast(0)
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}

/**
 * What Candle is playing, for the phone remote. The video player and the music service
 * report on every player event; the phone reads [current] from another thread.
 */
object NowPlaying {
    private val snapshots = ConcurrentHashMap<PlaybackKind, PlaybackSnapshot>()

    fun report(snapshot: PlaybackSnapshot) {
        snapshots[snapshot.kind] = snapshot
    }

    fun clear(kind: PlaybackKind) {
        snapshots.remove(kind)
    }

    fun current(): PlaybackSnapshot? = pick(snapshots.values)

    fun of(kind: PlaybackKind): PlaybackSnapshot? = snapshots[kind]

    /** A playing player wins (the latest report); otherwise the most recently reported paused one. */
    fun pick(snapshots: Collection<PlaybackSnapshot>): PlaybackSnapshot? =
        snapshots.filter { it.playing }.maxByOrNull { it.reportedAt } ?: snapshots.maxByOrNull { it.reportedAt }
}

/**
 * Reports a player's state to [NowPlaying]. Remove it with [NowPlaying.clear] when the player goes away.
 * [isCurrent] silences a video player that a newer one replaced, so its last pause does not overwrite the new state.
 */
class NowPlayingReporter(private val kind: PlaybackKind, private val isCurrent: () -> Boolean = { true }) : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) {
        if (!isCurrent()) return
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return NowPlaying.clear(kind)
        val item = player.currentMediaItem
        val title = player.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: item?.mediaId?.let { File(it).nameWithoutExtension }.orEmpty()
        NowPlaying.report(
            PlaybackSnapshot(
                kind,
                title,
                player.isPlaying,
                player.currentPosition,
                player.duration.coerceAtLeast(0),
                SystemClock.elapsedRealtime(),
                item?.localConfiguration?.uri?.toString().orEmpty(),
                player.playWhenReady,
            ),
        )
    }
}
