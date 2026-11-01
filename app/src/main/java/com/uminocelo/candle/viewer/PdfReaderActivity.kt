package com.uminocelo.candle.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import com.uminocelo.candle.player.PlayerKeys
import com.uminocelo.candle.player.ReaderAction
import java.io.File
import java.util.concurrent.Executors

/**
 * Reads a PDF with the remote. Network documents are copied to the cache first, since
 * Android's renderer needs a file it can seek in. Only the part of the page on screen
 * is rendered, at screen resolution, so a zoomed page costs no more memory than a
 * whole one. The renderer is not thread-safe: everything that touches it, the
 * viewport included, runs on one worker thread.
 */
class PdfReaderActivity : Activity() {
    private lateinit var source: MediaSource
    private lateinit var item: MediaItem
    private lateinit var pageView: ImageView
    private lateinit var caption: TextView
    private lateinit var message: TextView
    private lateinit var positions: ReadingPositions

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val hideCaption = Runnable { caption.visibility = View.GONE }

    // Worker thread only.
    private var file: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var viewport: PdfViewport? = null
    private var pageSizes = IntArray(0)

    /** Two bitmaps, so the worker never draws into the one on screen. */
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var front = 0
    private val paper = Paint().apply { color = Color.WHITE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = ViewerSession.current() ?: return finish()
        source = session.source
        item = session.items[session.start]
        setContentView(R.layout.activity_reader)
        pageView = findViewById(R.id.page)
        caption = findViewById(R.id.caption)
        message = findViewById(R.id.message)
        positions = ReadingPositions(PrefsPageStore(getSharedPreferences("reading", MODE_PRIVATE)))
        // Reading is slow and the remote is still: the projector must not sleep mid-page.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        message.text = getString(R.string.reader_copying, item.name)
        val metrics = resources.displayMetrics
        worker.execute { open(metrics.widthPixels, metrics.heightPixels) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val action = PlayerKeys.readerAction(keyCode) ?: return super.onKeyDown(keyCode, event)
        worker.execute { move(action) }
        return true
    }

    override fun onDestroy() {
        worker.execute {
            renderer?.close()
            file?.close()
        }
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun open(screenWidth: Int, screenHeight: Int) {
        val started = SystemClock.elapsedRealtime()
        try {
            val local = DocumentCache(File(cacheDir, "documents")).local(source, item) { copied, total ->
                if (total != null && total > 0) {
                    val percent = (copied * 100 / total).toInt()
                    main.post { message.text = getString(R.string.reader_copying_percent, item.name, percent) }
                }
            }
            val descriptor = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).also { file = it }
            val pdf = PdfRenderer(descriptor).also { renderer = it }
            pageSizes = IntArray(pdf.pageCount * 2)
            val start = positions.startPage(source.id, item.path, pdf.pageCount)
            viewport = PdfViewport(pdf.pageCount, screenWidth, screenHeight, start, ::pageSize)
            render()
            Log.i(TAG, "opened ${item.name}: ${pdf.pageCount} pages, page ${start + 1} in ${SystemClock.elapsedRealtime() - started} ms")
            if (start > 0) main.post { showCaption(getString(R.string.reader_continuing, start + 1, pdf.pageCount)) }
        } catch (e: Exception) {
            // Damaged files and password-protected PDFs (SecurityException) end up here.
            Log.w(TAG, "cannot open ${item.name}", e)
            main.post {
                message.text = getString(R.string.reader_unreadable, e.message ?: e.javaClass.simpleName)
                message.visibility = View.VISIBLE
            }
        }
    }

    private fun move(action: ReaderAction) {
        val view = viewport ?: return
        val page = view.page
        val zoom = view.zoom
        val moved = when (action) {
            ReaderAction.UP -> view.up()
            ReaderAction.DOWN -> view.down()
            ReaderAction.LEFT -> view.left()
            ReaderAction.RIGHT -> view.right()
            ReaderAction.NEXT_PAGE -> view.nextPage()
            ReaderAction.PREVIOUS_PAGE -> view.previousPage()
            ReaderAction.ZOOM -> {
                view.nextZoom()
                true
            }
        }
        if (!moved) return
        render()
        if (view.page != page) positions.save(source.id, item.path, view.page)
        if (view.page != page || view.zoom != zoom) {
            val text = getString(R.string.reader_caption, view.page + 1, view.pageCount, getString(zoomLabel(view.zoom)))
            main.post { showCaption(text) }
        }
    }

    /** A page's size in PDF points, read once per page. */
    private fun pageSize(index: Int): Pair<Int, Int> {
        if (pageSizes[index * 2] == 0) {
            renderer!!.openPage(index).use {
                pageSizes[index * 2] = it.width
                pageSizes[index * 2 + 1] = it.height
            }
        }
        return pageSizes[index * 2] to pageSizes[index * 2 + 1]
    }

    /** Draws the visible part of the current page into the back buffer, then shows it. */
    private fun render() {
        val view = viewport ?: return
        val back = 1 - front
        val metrics = resources.displayMetrics
        val bitmap = buffers[back] ?: Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888).also { buffers[back] = it }
        val scale = view.scale.toFloat()
        val left = view.offsetX.toFloat()
        val top = view.offsetY.toFloat()
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        // The renderer leaves transparent areas as they are, and PDF pages are white paper.
        canvas.drawRect(left, top, left + view.contentWidth.toFloat(), top + view.contentHeight.toFloat(), paper)
        renderer!!.openPage(view.page).use { page ->
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(left, top)
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        front = back
        main.post {
            if (isDestroyed) return@post
            message.visibility = View.GONE
            pageView.setImageBitmap(bitmap)
        }
    }

    private fun showCaption(text: String) {
        caption.text = text
        caption.visibility = View.VISIBLE
        main.removeCallbacks(hideCaption)
        main.postDelayed(hideCaption, CAPTION_MS)
    }

    companion object {
        private const val TAG = "CandleReader"
        private const val CAPTION_MS = 3_000L
        private const val BACKGROUND = 0xFF1E1C1A.toInt()

        /** Only PDFs: EPUB, MOBI, comics and the rest are read on the phone, from the server's web page. */
        fun canRead(item: MediaItem): Boolean = item.name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

        fun intent(context: Context, source: MediaSource, item: MediaItem): Intent {
            ViewerSession.put(ViewerSession(source, listOf(item), 0))
            return Intent(context, PdfReaderActivity::class.java)
        }

        private fun zoomLabel(zoom: Zoom): Int = when (zoom) {
            Zoom.WIDTH -> R.string.zoom_width
            Zoom.WIDTH_150 -> R.string.zoom_width_150
            Zoom.WIDTH_200 -> R.string.zoom_width_200
            Zoom.PAGE -> R.string.zoom_page
        }
    }
}
