package com.uminocelo.candle.browse

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.uminocelo.candle.R
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaType
import com.uminocelo.candle.media.sortedForBrowsing
import java.util.concurrent.Executors

/** What the projector's browsers show: everything Candle can open. */
val SHOWN_ON_PROJECTOR = setOf(MediaType.FOLDER, MediaType.VIDEO, MediaType.AUDIO, MediaType.IMAGE, MediaType.DOCUMENT)

/** What the phone page lists: it can only play videos and music on the projector. */
val PLAYABLE = setOf(MediaType.FOLDER, MediaType.VIDEO, MediaType.AUDIO)

/** Items of the [shown] types in browsing order; everything else is left out. */
fun List<MediaItem>.browsable(shown: Set<MediaType> = SHOWN_ON_PROJECTOR): List<MediaItem> =
    filter { it.type in shown }.sortedForBrowsing()

private const val TAG = "CandleBrowse"

/** A list row: OK calls [open]; a long press of OK (or Menu) calls [menu] when there is one. [icon] shows the kind of file. */
class BrowserRow(val label: String, val detail: String, val menu: (() -> Unit)? = null, val icon: Int? = null, val open: () -> Unit)

/** The row icon for a kind of item. */
fun rowIcon(type: MediaType): Int? = when (type) {
    MediaType.FOLDER -> R.drawable.ic_row_folder
    MediaType.VIDEO -> R.drawable.ic_row_video
    MediaType.AUDIO -> R.drawable.ic_row_audio
    MediaType.IMAGE -> R.drawable.ic_row_image
    MediaType.DOCUMENT -> R.drawable.ic_row_document
    MediaType.SUBTITLE, MediaType.OTHER -> null
}

/**
 * The list on a browser screen (`activity_browser`): a top level the screen fills
 * (drives, servers), then the folders of one [MediaSource]. Lists load off the main
 * thread; OK opens a row. Call [close] from `onDestroy`.
 */
class FolderBrowser(
    private val activity: Activity,
    /** Called when the user picks a file, with the open folder's files (a photo viewer pages through them). */
    private val openFile: (MediaSource, MediaItem, List<MediaItem>) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val title: TextView = activity.findViewById(R.id.title)
    private val list: ListView = activity.findViewById(R.id.list)
    private val message: TextView = activity.findViewById(R.id.message)
    private val adapter = RowAdapter()
    private var request = 0

    /** The open source, or null while the top level shows. */
    var source: MediaSource? = null
        private set
    private var breadcrumb: (String) -> String = { it }

    /** Folders entered inside [source], with the row selected in the folder above each. */
    private val folders = ArrayDeque<Pair<String, Int>>()

    val selectedPosition: Int get() = list.selectedItemPosition

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> adapter.rows[position].open() }
        list.setOnItemLongClickListener { _, _, position, _ -> openMenu(adapter.rows[position]) }
    }

    /** Opens the selected row's menu, for screens that also listen to the Menu key. */
    fun openSelectedMenu(): Boolean = adapter.rows.getOrNull(list.selectedItemPosition)?.let(::openMenu) ?: false

    private fun openMenu(row: BrowserRow): Boolean {
        val menu = row.menu ?: return false
        menu()
        return true
    }

    /** Shows the screen's top level and closes any open source. */
    fun <T> showTop(titleText: CharSequence, emptyText: Int, select: Int, work: () -> List<T>, toRow: (T) -> BrowserRow) {
        source = null
        folders.clear()
        title.text = titleText
        load(select, emptyText, work, toRow)
    }

    /** Opens [newSource] at its root; [crumb] turns a folder path into the title. */
    fun openSource(newSource: MediaSource, crumb: (String) -> String) {
        source = newSource
        breadcrumb = crumb
        folders.clear()
        showFolder()
    }

    /** Goes up one folder. Returns false at the source root, where the screen shows its top level. */
    fun up(): Boolean {
        if (source == null || folders.isEmpty()) return false
        showFolder(select = folders.removeLast().second)
        return true
    }

    fun close() {
        worker.shutdownNow()
    }

    private fun showFolder(select: Int = 0) {
        val open = source ?: return
        val path = folders.lastOrNull()?.first ?: MediaSource.ROOT
        title.text = breadcrumb(path)
        var items: List<MediaItem> = emptyList()
        load(select, R.string.folder_empty, { open.list(path).browsable().also { items = it } }) { item -> rowFor(open, item) { items } }
    }

    private fun rowFor(open: MediaSource, item: MediaItem, folder: () -> List<MediaItem>): BrowserRow =
        if (item.isFolder) {
            BrowserRow(item.name, "", icon = rowIcon(item.type)) {
                folders.addLast(item.path to list.selectedItemPosition)
                showFolder()
            }
        } else {
            val size = item.size?.let { Formatter.formatShortFileSize(activity, it) }.orEmpty()
            BrowserRow(item.name, size, icon = rowIcon(item.type)) { openFile(open, item, folder().filter { !it.isFolder }) }
        }

    /** Runs [work] off the main thread and shows its result; results of superseded requests are dropped. */
    private fun <T> load(select: Int, emptyText: Int, work: () -> List<T>, toRow: (T) -> BrowserRow) {
        val id = ++request
        adapter.show(emptyList())
        message.visibility = View.GONE
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            val result = runCatching(work)
            Log.i(TAG, "loaded ${result.getOrNull()?.size ?: "nothing"} in ${SystemClock.elapsedRealtime() - started} ms")
            main.post {
                if (id != request || activity.isDestroyed) return@post
                val rows = result.getOrNull()?.map(toRow)
                adapter.show(rows.orEmpty())
                when {
                    rows == null -> showMessage(errorText(result.exceptionOrNull()))
                    rows.isEmpty() -> showMessage(activity.getString(emptyText))
                    else -> {
                        list.setSelection(select.coerceIn(0, rows.lastIndex))
                        list.requestFocus()
                    }
                }
            }
        }
    }

    private fun errorText(error: Throwable?): String {
        val reason = generateSequence(error) { it.cause }.mapNotNull { it.message }.firstOrNull()
        val unreadable = activity.getString(R.string.folder_unreadable)
        return if (reason == null) unreadable else "$unreadable\n$reason"
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<BrowserRow> = emptyList()
            private set

        fun show(newRows: List<BrowserRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_browser, parent, false)
            val row = rows[position]
            view.findViewById<TextView>(R.id.name).text = row.label
            view.findViewById<TextView>(R.id.detail).text = row.detail
            view.findViewById<ImageView>(R.id.icon).apply {
                visibility = if (row.icon == null) View.GONE else View.VISIBLE
                row.icon?.let(::setImageResource)
            }
            return view
        }
    }
}
