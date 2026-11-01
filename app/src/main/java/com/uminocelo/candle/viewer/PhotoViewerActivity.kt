package com.uminocelo.candle.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.uminocelo.candle.R
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.player.PhotoAction
import com.uminocelo.candle.player.PlayerKeys
import java.util.concurrent.Executors

/**
 * Full-screen photos from one folder of any source. Left and right page through them
 * (wrapping around), OK starts or stops a slideshow. Each photo is decoded at screen
 * size off the main thread, and the next one is decoded ahead so paging feels instant.
 */
class PhotoViewerActivity : Activity() {
    private lateinit var source: MediaSource
    private lateinit var photos: List<MediaItem>
    private lateinit var pager: PhotoPager
    private lateinit var loader: PhotoLoader
    private lateinit var photoView: ImageView
    private lateinit var caption: TextView
    private lateinit var message: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Decoded photos by index: the one showing and the one decoded ahead, nothing more. */
    private val decoded = HashMap<Int, Bitmap>()
    private var request = 0

    private val advance = Runnable {
        show(pager.next())
    }
    private val hideCaption = Runnable { caption.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The folder comes from the browser in this process; after Android restarts Candle there is none.
        val session = ViewerSession.current() ?: return finish()
        source = session.source
        photos = session.items
        pager = PhotoPager(photos.size, session.start)
        setContentView(R.layout.activity_photo)
        photoView = findViewById(R.id.photo)
        caption = findViewById(R.id.caption)
        message = findViewById(R.id.message)
        val metrics = resources.displayMetrics
        loader = PhotoLoader(this, metrics.widthPixels, metrics.heightPixels)
        show(pager.index)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (PlayerKeys.photoAction(keyCode) ?: return super.onKeyDown(keyCode, event)) {
            PhotoAction.PREVIOUS -> show(pager.previous())
            PhotoAction.NEXT -> show(pager.next())
            PhotoAction.TOGGLE_SLIDESHOW -> {
                pager.toggleSlideshow()
                updateCaption()
                scheduleSlide()
            }
            PhotoAction.SHOW_CAPTION -> updateCaption()
        }
        return true
    }

    override fun onPause() {
        // The slideshow stops when Candle leaves the screen; OK starts it again.
        if (pager.slideshow) pager.toggleSlideshow()
        main.removeCallbacks(advance)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        if (::photoView.isInitialized) photoView.setImageDrawable(null)
        synchronized(decoded) { decoded.clear() }
        super.onDestroy()
    }

    private fun show(index: Int) {
        val id = ++request
        main.removeCallbacks(advance)
        updateCaption()
        val ready = synchronized(decoded) { decoded[index] }
        if (ready != null) {
            display(ready)
        } else {
            message.setText(R.string.photo_loading)
            message.visibility = View.VISIBLE
        }
        worker.execute {
            val bitmap = if (ready != null) Result.success(ready) else decode(index)
            main.post {
                if (id != request || isDestroyed) return@post
                bitmap.onSuccess(::display).onFailure { error ->
                    photoView.setImageDrawable(null)
                    message.text = getString(R.string.photo_unreadable, error.message ?: error.javaClass.simpleName)
                    message.visibility = View.VISIBLE
                }
                scheduleSlide()
            }
            // Ahead of time, so right (or the slideshow) shows the next photo at once.
            val next = pager.upcoming
            if (next != index) decode(next)
            synchronized(decoded) { decoded.keys.retainAll(setOf(index, next)) }
        }
    }

    /** Decodes [index] unless already done; on the worker thread. */
    private fun decode(index: Int): Result<Bitmap> {
        synchronized(decoded) { decoded[index] }?.let { return Result.success(it) }
        val started = SystemClock.elapsedRealtime()
        return runCatching { loader.decode(source, photos[index]) }
            .onSuccess {
                Log.i(TAG, "decoded ${photos[index].name} at ${it.width}x${it.height} in ${SystemClock.elapsedRealtime() - started} ms")
                synchronized(decoded) { decoded[index] = it }
            }
            .onFailure { Log.w(TAG, "cannot decode ${photos[index].name}", it) }
    }

    private fun display(bitmap: Bitmap) {
        message.visibility = View.GONE
        photoView.setImageBitmap(bitmap)
    }

    private fun updateCaption() {
        val base = getString(R.string.photo_caption, photos[pager.index].name, pager.index + 1, photos.size)
        caption.text = if (pager.slideshow) getString(R.string.photo_slideshow, base) else base
        caption.visibility = View.VISIBLE
        main.removeCallbacks(hideCaption)
        main.postDelayed(hideCaption, CAPTION_MS)
    }

    private fun scheduleSlide() {
        main.removeCallbacks(advance)
        if (pager.slideshow) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            main.postDelayed(advance, PhotoPager.SLIDE_MS)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        private const val TAG = "CandlePhotos"
        private const val CAPTION_MS = 3_000L

        /** Opens [start] among [photos] (the folder's photos in browsing order) of [source]. */
        fun intent(context: Context, source: MediaSource, photos: List<MediaItem>, start: MediaItem): Intent {
            ViewerSession.put(ViewerSession(source, photos, photos.indexOf(start).coerceAtLeast(0)))
            return Intent(context, PhotoViewerActivity::class.java)
        }
    }
}
