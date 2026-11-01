package com.uminocelo.candle.viewer

/**
 * Which photo of a folder shows, and whether the slideshow runs. Moving past either
 * end wraps around, so a slideshow keeps going through the folder.
 */
class PhotoPager(private val count: Int, start: Int) {
    init {
        require(count > 0) { "no photos" }
    }

    var index: Int = start.coerceIn(0, count - 1)
        private set

    var slideshow: Boolean = false
        private set

    fun next(): Int {
        index = (index + 1) % count
        return index
    }

    fun previous(): Int {
        index = (index - 1 + count) % count
        return index
    }

    /** OK on the remote: starts or stops the slideshow; returns whether it now runs. */
    fun toggleSlideshow(): Boolean {
        slideshow = !slideshow && count > 1
        return slideshow
    }

    /** The photo shown after this one, to decode ahead of time. */
    val upcoming: Int get() = (index + 1) % count

    companion object {
        const val SLIDE_MS = 5_000L
    }
}

/**
 * The size to decode a [width]×[height] photo at so it fits the screen and no more:
 * a 4032×3024 photo becomes 960×720 on a 1280×720 projector. Long side against long
 * side, so it holds for portrait photos too (the camera's rotation is applied after
 * decoding). Photos smaller than the screen keep their size.
 */
fun fitSize(width: Int, height: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
    val scale = minOf(
        maxOf(screenWidth, screenHeight).toDouble() / maxOf(width, height),
        minOf(screenWidth, screenHeight).toDouble() / minOf(width, height),
        1.0,
    )
    return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
}
