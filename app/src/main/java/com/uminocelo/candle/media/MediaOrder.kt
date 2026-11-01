package com.uminocelo.candle.media

/** Folders first, then natural order by name ("Ep2" before "Ep10"), ignoring case. */
fun List<MediaItem>.sortedForBrowsing(): List<MediaItem> =
    sortedWith(compareBy<MediaItem> { !it.isFolder }.thenBy(NaturalOrder) { it.name })

object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val result = if (a[i].isDigit() && b[j].isDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                compareNumbers(a.substring(i, endA), b.substring(j, endB)).also { i = endA; j = endB }
            } else {
                a[i].lowercaseChar().compareTo(b[j].lowercaseChar()).also { i++; j++ }
            }
            if (result != 0) return result
        }
        val byLength = (a.length - i).compareTo(b.length - j)
        // Equal ignoring case and leading zeros: fall back to a plain comparison so the order is total.
        return if (byLength != 0) byLength else a.compareTo(b)
    }

    private fun digitRunEnd(s: String, start: Int): Int {
        var end = start
        while (end < s.length && s[end].isDigit()) end++
        return end
    }

    // Compares digit runs of any length without overflowing a Long.
    private fun compareNumbers(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }
}
