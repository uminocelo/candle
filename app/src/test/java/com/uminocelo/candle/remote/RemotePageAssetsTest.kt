package com.uminocelo.candle.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/** The phone page's home-screen app files: the manifest, the icons it names, and the page's links to them. */
class RemotePageAssetsTest {
    // Unit tests run from the module directory.
    private val dir = File("src/main/assets/remote")
    private val manifest = File(dir, "manifest.json").readText()
    private val page = File(dir, "index.html").readText()

    /** Width and height from a PNG's IHDR chunk. */
    private fun pngSize(file: File): Pair<Int, Int> = DataInputStream(file.inputStream()).use {
        it.skipBytes(16)
        it.readInt() to it.readInt()
    }

    @Test
    fun `the manifest makes a standalone app that opens the remote`() {
        for (field in listOf("\"start_url\": \"/\"", "\"scope\": \"/\"", "\"display\": \"standalone\"", "\"short_name\": \"Candle\"")) {
            assertTrue(field, field in manifest)
        }
        assertTrue("a maskable icon for Android's shapes", "\"purpose\": \"maskable\"" in manifest)
    }

    @Test
    fun `every icon the manifest names exists at its stated size`() {
        val icons = Regex(""""src": "([^"]+)", "sizes": "([^"]+)"""").findAll(manifest).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(icons.size >= 3)
        for ((src, sizes) in icons) {
            val file = File(dir, src)
            assertTrue("$src exists", file.isFile)
            if (src.endsWith(".png")) {
                val (width, height) = pngSize(file)
                assertEquals(src, sizes, "${width}x$height")
            }
        }
        assertEquals(180 to 180, pngSize(File(dir, "apple-touch-icon.png")))
    }

    @Test
    fun `the page links the manifest, icons and service worker, all served with the right types`() {
        for (link in listOf("href=\"manifest.json\"", "href=\"apple-touch-icon.png\"", "register(\"sw.js\")")) {
            assertTrue(link, link in page)
        }
        assertTrue(File(dir, "sw.js").isFile)
        assertEquals("image/png", RemoteApi.contentType("icon-192.png"))
        assertEquals("image/svg+xml", RemoteApi.contentType("icon.svg"))
        assertTrue(RemoteApi.contentType("manifest.json").startsWith("application/json"))
        assertTrue(RemoteApi.contentType("sw.js").startsWith("text/javascript"))
    }
}
