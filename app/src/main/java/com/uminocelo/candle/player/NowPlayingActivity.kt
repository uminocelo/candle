package com.uminocelo.candle.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.TextView
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.uminocelo.candle.R
import com.uminocelo.candle.media.LocalFileSource
import com.uminocelo.candle.resume.formatDuration
import java.io.File
import androidx.media3.common.MediaItem as PlayerItem

/**
 * Shows and controls [MusicService]. Opened with a folder (and optionally a track in it),
 * it replaces the queue; opened without one, it shows what is already playing.
 */
class NowPlayingActivity : Activity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    private val progressTick = object : Runnable {
        override fun run() {
            showProgress()
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.let(::startQueueFromIntent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture.addListener({
            val connected = controllerFuture.get()
            controller = connected
            connected.addListener(uiListener)
            startQueueFromIntent(connected)
            showTrack()
            handler.post(progressTick)
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(progressTick)
        controller?.removeListener(uiListener)
        controller = null
        MediaController.releaseFuture(controllerFuture)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val player = controller ?: return super.dispatchKeyEvent(event)
        val action = PlayerKeys.musicAction(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (action) {
                MusicAction.PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
                MusicAction.PREVIOUS -> player.seekToPrevious()
                MusicAction.NEXT -> player.seekToNext()
                MusicAction.TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                MusicAction.CYCLE_REPEAT -> player.repeatMode = MusicQueue.nextRepeatMode(player.repeatMode)
            }
        }
        return true
    }

    /** Consumes the queue request once, so returning to this screen does not restart the queue. */
    private fun startQueueFromIntent(player: MediaController) {
        val viewedFile = intent.data?.takeIf { it.scheme == "file" }?.path?.let(::File)
        val folder = intent.getStringExtra(EXTRA_FOLDER) ?: viewedFile?.parent ?: return
        val startPath = intent.getStringExtra(EXTRA_START_PATH) ?: viewedFile?.path
        setIntent(Intent())

        val tracks = MusicQueue.tracksIn(LocalFileSource(folder).list(folder))
        if (tracks.isEmpty()) return
        val items = tracks.map { track ->
            PlayerItem.Builder()
                .setMediaId(track.path)
                .setRequestMetadata(
                    PlayerItem.RequestMetadata.Builder().setMediaUri(Uri.fromFile(File(track.path))).build(),
                )
                // displayTitle is the fallback: the file's own title tag wins when it has one.
                .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(File(track.name).nameWithoutExtension).build())
                .build()
        }
        val startIndex = tracks.indexOfFirst { it.path == startPath }.coerceAtLeast(0)
        player.setMediaItems(items, startIndex, 0)
        player.prepare()
        player.play()
    }

    private val uiListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = showTrack()
    }

    private fun showTrack() {
        val player = controller ?: return
        val metadata = player.mediaMetadata
        findViewById<TextView>(R.id.title).text =
            metadata.title ?: metadata.displayTitle ?: getString(R.string.nothing_playing)
        findViewById<TextView>(R.id.artist).text = listOfNotNull(metadata.artist, metadata.albumTitle).joinToString(" · ")
        findViewById<TextView>(R.id.modes).text = getString(
            R.string.music_modes,
            getString(if (player.shuffleModeEnabled) R.string.on else R.string.off),
            getString(repeatLabel(player.repeatMode)),
            if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1,
            player.mediaItemCount,
        )
        findViewById<TextView>(R.id.state).text = getString(if (player.isPlaying) R.string.playing else R.string.paused)
        showProgress()
    }

    private fun showProgress() {
        val player = controller ?: return
        findViewById<TextView>(R.id.progress).text =
            "${formatDuration(player.currentPosition)} / ${formatDuration(player.duration.coerceAtLeast(0))}"
    }

    private fun repeatLabel(mode: Int) = when (mode) {
        Player.REPEAT_MODE_ALL -> R.string.repeat_all
        Player.REPEAT_MODE_ONE -> R.string.repeat_one
        else -> R.string.off
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_START_PATH = "start_path"

        /** Plays the audio files in [folder], starting at [startPath] when given. */
        fun intent(context: Context, folder: String, startPath: String? = null): Intent =
            Intent(context, NowPlayingActivity::class.java)
                .putExtra(EXTRA_FOLDER, folder)
                .putExtra(EXTRA_START_PATH, startPath)
    }
}
