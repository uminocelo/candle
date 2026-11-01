package com.uminocelo.candle.player

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.uminocelo.candle.usb.watchDrives
import kotlin.random.Random

/**
 * Background music. The media session keeps playing after Now Playing closes and
 * receives the remote's media keys from any screen.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null
    private var driveWatch: AutoCloseable? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, renderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // The phone remote's seek buttons send rewind/fast-forward keys to this session.
            .setSeekBackIncrementMs(PlayerKeys.SEEK_STEP_MS)
            .setSeekForwardIncrementMs(PlayerKeys.SEEK_STEP_MS)
            .build()
        player.addListener(ShuffleFromCurrent(player))
        player.addListener(NowPlayingReporter(PlaybackKind.MUSIC))
        session = MediaSession.Builder(this, player).setCallback(ResolveUris).build()
        // Media ids are file paths (NowPlayingActivity), so a removed drive empties a queue that lives on it.
        driveWatch = watchDrives { event ->
            val current = player.currentMediaItem?.mediaId
            if (!event.mounted && current != null && event.affects(current)) {
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        driveWatch?.close()
        session?.run {
            player.release()
            release()
        }
        session = null
        NowPlaying.clear(PlaybackKind.MUSIC)
        super.onDestroy()
    }

    /** Controllers send URIs in request metadata; local configuration does not cross the session boundary. */
    private object ResolveUris : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item
            }.toMutableList(),
        )
    }

    /** Re-shuffles so the current track comes first whenever shuffle is turned on or the queue changes. */
    private class ShuffleFromCurrent(private val player: ExoPlayer) : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_TIMELINE_CHANGED) &&
                player.shuffleModeEnabled && player.mediaItemCount > 0
            ) {
                val seed = Random.nextLong()
                val order = MusicQueue.shuffledIndices(player.mediaItemCount, player.currentMediaItemIndex, Random(seed))
                this.player.setShuffleOrder(DefaultShuffleOrder(order, seed))
            }
        }
    }
}
