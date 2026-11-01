package com.uminocelo.candle.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.uminocelo.candle.R
import com.uminocelo.candle.resume.ResumeStore
import com.uminocelo.candle.resume.formatDuration
import com.uminocelo.candle.resume.resumeStore
import com.uminocelo.candle.usb.watchDrives
import java.io.File

/**
 * Full-screen video playback driven by the remote: OK play/pause, left/right seek
 * 10 s, Menu for audio and subtitle tracks. Saves the position every 10 s.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : Activity() {
    private lateinit var target: PlaybackTarget
    private lateinit var resumeStore: ResumeStore
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private val handler = Handler(Looper.getMainLooper())
    private var playbackStarted = false
    private var droppedFrames = 0
    private var driveWatch: AutoCloseable? = null
    private val okPress = OkPress(ViewConfiguration.getLongPressTimeout().toLong())

    /** Lets the DLNA renderer drive this player; every command runs on the main thread. */
    private val commands = object : VideoCommands {
        override fun play() = onMain { player.play() }

        override fun pause() = onMain { player.pause() }

        override fun seekTo(positionMs: Long) = onMain { player.seekTo(positionMs) }

        override fun close() = onMain { finish() }

        private fun onMain(action: () -> Unit) {
            handler.post { if (::player.isInitialized && !isDestroyed) action() }
        }
    }

    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            logFrames()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = targetFrom(intent) ?: return finish()
        resumeStore = resumeStore()
        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        player = ExoPlayer.Builder(this, renderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(candleDataSourceFactory(this)))
            .build()
        player.addListener(playerListener)
        ActiveVideo.open(commands)
        player.addListener(NowPlayingReporter(PlaybackKind.VIDEO) { ActiveVideo.isActive(commands) })
        player.addAnalyticsListener(frameListener)
        playerView = findViewById(R.id.player_view)
        playerView.player = player
        player.setMediaItem(mediaItemFor(target))
        player.prepare()
        if (target.isLocalFile) driveWatch = watchDrives { event -> if (!event.mounted && event.affects(target.path)) leaveRemovedDrive() }

        val saved = resumeStore.entries().firstOrNull { it.sourceId == target.sourceId && it.path == target.path }
        when {
            !ResumePolicy.shouldOffer(saved) -> player.play()
            intent.getBooleanExtra(EXTRA_START_AT_SAVED, false) -> playFrom(saved!!.positionMs)
            else -> askToResume(saved!!.positionMs)
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(saveTick)
        if (::player.isInitialized) {
            player.pause()
            savePosition()
            logFrames()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        driveWatch?.close()
        if (::player.isInitialized) player.release()
        // A newer player (another video sent from a phone) already reports; leave its state alone.
        if (ActiveVideo.release(commands)) NowPlaying.clear(PlaybackKind.VIDEO)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PlayerKeys.isOk(event.keyCode)) {
            val action = when (event.action) {
                KeyEvent.ACTION_DOWN -> okPress.down(event.repeatCount, event.eventTime - event.downTime, event.isLongPress)
                KeyEvent.ACTION_UP -> okPress.up(event.isCanceled)
                else -> null
            }
            action?.let(::perform)
            return true
        }
        val action = PlayerKeys.videoAction(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) perform(action)
        return true
    }

    private fun perform(action: VideoAction) {
        when (action) {
            VideoAction.PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
            VideoAction.PLAY -> player.play()
            VideoAction.PAUSE -> player.pause()
            VideoAction.SEEK_BACK -> seekBy(-PlayerKeys.SEEK_STEP_MS)
            VideoAction.SEEK_FORWARD -> seekBy(PlayerKeys.SEEK_STEP_MS)
            VideoAction.TRACKS -> showTrackMenu()
            VideoAction.SHOW_CONTROLS -> Unit
        }
        playerView.showController()
    }

    /** The file's drive is going away: stop reading it now; [onStop] still saves the position. */
    private fun leaveRemovedDrive() {
        player.pause()
        Toast.makeText(this, R.string.drive_removed, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun seekBy(deltaMs: Long) =
        player.seekTo(PlayerKeys.seekTarget(player.currentPosition, deltaMs, player.duration))

    private fun playFrom(positionMs: Long) {
        player.seekTo(positionMs)
        player.play()
    }

    private fun askToResume(positionMs: Long) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(target.title)
            .setMessage(getString(R.string.resume_question, formatDuration(positionMs)))
            .setPositiveButton(R.string.resume) { _, _ -> playFrom(positionMs) }
            .setNegativeButton(R.string.start_over) { _, _ -> player.play() }
            .setOnCancelListener { finish() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }

    private fun showTrackMenu() {
        val kinds = intArrayOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)
        val labels = arrayOf(getString(R.string.tracks_audio), getString(R.string.tracks_subtitles))
        AlertDialog.Builder(this)
            .setTitle(R.string.tracks_title)
            .setItems(labels) { _, which ->
                TrackSelectionDialogBuilder(this, labels[which], player, kinds[which])
                    .setShowDisableOption(kinds[which] == C.TRACK_TYPE_TEXT)
                    .setAllowAdaptiveSelections(false)
                    .build()
                    .show()
            }
            .show()
    }

    private fun savePosition() {
        if (!playbackStarted) return
        val entry = ResumePolicy.entryFor(target, player.currentPosition, player.duration, System.currentTimeMillis())
        if (entry != null) resumeStore.save(entry) else resumeStore.remove(target.sourceId, target.path)
    }

    // Decoder drops are what the "no dropped frames" check reads (adb logcat -s CandlePlayer).
    private fun logFrames() {
        val counters = player.videoDecoderCounters ?: return
        Log.i(TAG, "frames: rendered=${counters.renderedOutputBufferCount} dropped=$droppedFrames position=${player.currentPosition}")
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && !playbackStarted) {
                playbackStarted = true
                handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            // Buffering → ready pairs show stalls and how long a seek takes to resume.
            if (state == Player.STATE_BUFFERING || state == Player.STATE_READY) {
                Log.i(TAG, "${if (state == Player.STATE_READY) "ready" else "buffering"} at ${player.currentPosition} ms")
            }
            if (state == Player.STATE_ENDED) {
                resumeStore.remove(target.sourceId, target.path)
                playbackStarted = false
                finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playback failed: ${target.uri}", error)
            AlertDialog.Builder(this@VideoPlayerActivity)
                .setTitle(R.string.playback_error)
                .setMessage(error.errorCodeName)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private val frameListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
            droppedFrames += count
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) {
            Log.i(TAG, "video decoder: $decoderName")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
        ) {
            Log.i(TAG, "audio decoder: $decoderName")
        }
    }

    companion object {
        private const val TAG = "CandlePlayer"
        private const val SAVE_INTERVAL_MS = 10_000L
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_START_AT_SAVED = "start_at_saved"

        fun intent(context: Context, target: PlaybackTarget, startAtSaved: Boolean = false): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_SOURCE, target.sourceId)
                .putExtra(EXTRA_PATH, target.path)
                .putExtra(EXTRA_TITLE, target.title)
                .putExtra(EXTRA_URI, target.uri)
                .putExtra(EXTRA_START_AT_SAVED, startAtSaved)

        /** Our own extras, or a VIEW intent (from adb or another app). */
        private fun targetFrom(intent: Intent): PlaybackTarget? {
            intent.getStringExtra(EXTRA_URI)?.let { uri ->
                return PlaybackTarget(
                    intent.getStringExtra(EXTRA_SOURCE) ?: PlaybackTarget.URI_SOURCE,
                    intent.getStringExtra(EXTRA_PATH) ?: uri,
                    intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    uri,
                )
            }
            val data: Uri = intent.data ?: return null
            val path = data.path
            return if (data.scheme == "file" && path != null) {
                PlaybackTarget.localFile(path)
            } else {
                PlaybackTarget.uri(data.toString(), data.lastPathSegment.orEmpty())
            }
        }

        private fun mediaItemFor(target: PlaybackTarget): MediaItem {
            val builder = MediaItem.Builder()
                .setUri(target.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(target.title).build())
            if (target.isLocalFile) {
                val video = File(target.path)
                val siblings = video.parentFile?.list()?.toList().orEmpty()
                builder.setSubtitleConfigurations(
                    sidecarSubtitles(video.name, siblings).map { sub ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(video.parentFile, sub.fileName)))
                            .setMimeType(sub.mimeType)
                            .setLanguage(sub.language)
                            .setLabel(sub.fileName)
                            .build()
                    },
                )
            }
            return builder.build()
        }
    }
}
