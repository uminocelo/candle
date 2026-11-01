package com.uminocelo.candle.viewer

/** Zoom steps of the reader; OK on the remote goes to the next, and the last wraps to the first. */
enum class Zoom(val widths: Double?) {
    /** The page as wide as the screen: the way to read on a 16:9 wall. */
    WIDTH(1.0),
    WIDTH_150(1.5),
    WIDTH_200(2.0),

    /** The whole page on screen, for an overview. */
    PAGE(null),
}

/**
 * Which part of which page the reader shows. Sizes are in pixels for the screen and
 * in PDF points for pages ([pageSize], which may differ from page to page). Down and
 * up scroll through a page and carry on to the next or previous one; right and left
 * pan when the page is wider than the screen, else turn the page. The first and last
 * pages are limits, not wrap points.
 */
class PdfViewport(
    val pageCount: Int,
    private val screenWidth: Int,
    private val screenHeight: Int,
    startPage: Int = 0,
    private val pageSize: (Int) -> Pair<Int, Int>,
) {
    init {
        require(pageCount > 0) { "the document has no pages" }
    }

    var page: Int = startPage.coerceIn(0, pageCount - 1)
        private set
    var zoom: Zoom = Zoom.WIDTH
        private set
    var scrollX = 0.0
        private set
    var scrollY = 0.0
        private set

    /** Pixels per PDF point for the current page and zoom. */
    val scale: Double
        get() {
            val (width, height) = pageSize(page)
            return zoom.widths?.let { it * screenWidth / width } ?: minOf(screenWidth.toDouble() / width, screenHeight.toDouble() / height)
        }

    val contentWidth: Double get() = pageSize(page).first * scale
    val contentHeight: Double get() = pageSize(page).second * scale
    private val maxScrollX: Double get() = maxOf(0.0, contentWidth - screenWidth)
    private val maxScrollY: Double get() = maxOf(0.0, contentHeight - screenHeight)

    /** Where the page's top-left corner lands on screen: centred when smaller than the screen. */
    val offsetX: Double get() = if (contentWidth < screenWidth) (screenWidth - contentWidth) / 2 else -scrollX
    val offsetY: Double get() = if (contentHeight < screenHeight) (screenHeight - contentHeight) / 2 else -scrollY

    private val stepY: Double get() = screenHeight * STEP
    private val stepX: Double get() = screenWidth * STEP

    /** Each move returns whether anything changed (false at the first or last page's edge). */
    fun down(): Boolean = if (scrollY < maxScrollY) {
        scrollY = minOf(maxScrollY, scrollY + stepY)
        true
    } else {
        goTo(page + 1, atBottom = false)
    }

    fun up(): Boolean = if (scrollY > 0) {
        scrollY = maxOf(0.0, scrollY - stepY)
        true
    } else {
        goTo(page - 1, atBottom = true)
    }

    fun right(): Boolean = if (scrollX < maxScrollX) {
        scrollX = minOf(maxScrollX, scrollX + stepX)
        true
    } else {
        goTo(page + 1, atBottom = false)
    }

    fun left(): Boolean = if (scrollX > 0) {
        scrollX = maxOf(0.0, scrollX - stepX)
        true
    } else {
        goTo(page - 1, atBottom = false)
    }

    /** Media next and previous, Page Down and Up: a whole page at a time. */
    fun nextPage(): Boolean = goTo(page + 1, atBottom = false)

    fun previousPage(): Boolean = goTo(page - 1, atBottom = false)

    /** The next zoom step, keeping the middle of the screen on the same part of the page. */
    fun nextZoom() {
        val middle = (scrollY + screenHeight / 2.0) / contentHeight
        zoom = Zoom.entries[(zoom.ordinal + 1) % Zoom.entries.size]
        scrollY = (middle * contentHeight - screenHeight / 2.0).coerceIn(0.0, maxScrollY)
        scrollX = maxScrollX / 2
    }

    private fun goTo(target: Int, atBottom: Boolean): Boolean {
        if (target !in 0 until pageCount) return false
        page = target
        scrollY = if (atBottom) maxScrollY else 0.0
        scrollX = if (zoom == Zoom.WIDTH || zoom == Zoom.PAGE) 0.0 else maxScrollX / 2
        return true
    }

    private companion object {
        /** A scroll step leaves a little of the last screen in view, so the eye keeps its place. */
        const val STEP = 0.85
    }
}
