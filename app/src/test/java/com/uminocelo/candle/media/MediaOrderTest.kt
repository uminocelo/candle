package com.uminocelo.candle.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOrderTest {
    private fun names(vararg names: String) = names.toList()

    @Test
    fun `numbers sort by value, not by character`() {
        assertEquals(
            names("Ep1", "Ep2", "Ep10", "Ep11"),
            names("Ep10", "Ep2", "Ep11", "Ep1").sortedWith(NaturalOrder),
        )
    }

    @Test
    fun `several number runs in one name`() {
        assertEquals(
            names("S1E2", "S1E10", "S2E1"),
            names("S2E1", "S1E10", "S1E2").sortedWith(NaturalOrder),
        )
    }

    @Test
    fun `ignores case`() {
        assertEquals(
            names("alpha", "Beta", "gamma"),
            names("gamma", "Beta", "alpha").sortedWith(NaturalOrder),
        )
    }

    @Test
    fun `leading zeros and huge numbers compare by value`() {
        assertTrue(NaturalOrder.compare("Ep02", "Ep10") < 0)
        assertTrue(NaturalOrder.compare("track99999999999999999999", "track100000000000000000000") < 0)
    }

    @Test
    fun `shorter prefix comes first and order is total`() {
        assertTrue(NaturalOrder.compare("Ep", "Ep1") < 0)
        assertTrue(NaturalOrder.compare("Ep1", "Ep01") != 0)
        assertEquals(0, NaturalOrder.compare("Ep1", "Ep1"))
    }

    @Test
    fun `folders come before files, each in natural order`() {
        val items = listOf(
            MediaItem.file("Ep10.mkv", "/Ep10.mkv"),
            MediaItem.folder("Season 10", "/Season 10"),
            MediaItem.file("Ep2.mkv", "/Ep2.mkv"),
            MediaItem.folder("Season 2", "/Season 2"),
        )
        assertEquals(
            names("Season 2", "Season 10", "Ep2.mkv", "Ep10.mkv"),
            items.sortedForBrowsing().map { it.name },
        )
    }
}
