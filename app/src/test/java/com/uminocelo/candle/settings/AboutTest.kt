package com.uminocelo.candle.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AboutTest {
    private val thirdParty = File("../THIRD_PARTY.md").readText()
    private val licence = File("../LICENSE").readText()
    private val text = aboutText("Candle", "0.9.0", thirdParty, licence)

    @Test
    fun `names the app, its version and where the source is`() {
        assertTrue(text.startsWith("Candle 0.9.0\nSource: https://github.com/uminocelo/candle\n"))
    }

    @Test
    fun `lists every library Candle ships`() {
        val rows = thirdParty.substringAfter("## Candle (projector)").substringBefore("## ").lines().filter { it.startsWith("| ") && ':' in it }
        assertTrue(rows.size >= 30)
        rows.forEach { assertTrue(it, it in text) }
    }

    @Test
    fun `carries the copyright and the full GPL`() {
        assertTrue("Copyright" in text && "uminocelo" in text)
        assertTrue("GNU GENERAL PUBLIC LICENSE" in text && "END OF TERMS AND CONDITIONS" in text)
    }
}
