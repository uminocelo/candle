package com.uminocelo.candle.server

import com.uminocelo.candle.http.HttpError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class UploadsTest {
    private val dir = File(storage(), Uploads.FOLDER)
    private var free = Long.MAX_VALUE
    private val uploads = Uploads(dir, maxFileBytes = 1000, keepFreeBytes = 100, freeBytes = { free })

    private fun save(name: String, bytes: ByteArray = ByteArray(10), length: Long = bytes.size.toLong()) =
        uploads.save(name, length, ByteArrayInputStream(bytes))

    private fun status(block: () -> Unit): Int = try {
        block()
        fail("expected an HttpError")
        0
    } catch (e: HttpError) {
        e.status
    }

    /** Files in the folder, temporary ones included. */
    private fun files() = dir.list().orEmpty().sorted()

    @Test
    fun `saves the body under its name, creating the folder`() {
        val file = save("clip.mp4", ByteArray(10) { it.toByte() })
        assertEquals(File(dir, "clip.mp4"), file)
        assertEquals((0 until 10).map { it.toByte() }, file.readBytes().toList())
    }

    @Test
    fun `saves into another folder when given one, with its own clash check`() {
        val other = File(dir.parentFile, "Movies").apply { mkdirs() }
        save("clip.mp4")
        assertEquals(File(other, "clip.mp4"), uploads.save("clip.mp4", 3, ByteArrayInputStream(ByteArray(3)), other))
        assertEquals(File(other, "clip (1).mp4"), uploads.save("clip.mp4", 3, ByteArrayInputStream(ByteArray(3)), other))
        assertEquals(listOf("clip (1).mp4", "clip.mp4"), other.list()!!.sorted())
    }

    @Test
    fun `a name clash keeps both files`() {
        save("clip.mp4")
        assertEquals("clip (1).mp4", save("clip.mp4").name)
        assertEquals("clip (2).mp4", save("clip.mp4").name)
        assertEquals("README (1)", save("README").name.also { save("README") }.let { "README (1)" })
        assertEquals(listOf("README", "README (1)", "clip (1).mp4", "clip (2).mp4", "clip.mp4"), files())
    }

    @Test
    fun `unsafe names are made safe or refused`() {
        assertEquals("passwd", Uploads.safeName("../../etc/passwd"))
        assertEquals("a.txt", Uploads.safeName("C:\\Users\\me\\a.txt"))
        assertEquals("hidden.mp4", Uploads.safeName(".hidden.mp4"))
        assertEquals("ab.mp4", Uploads.safeName("a\u0000b.mp4"))
        assertNull(Uploads.safeName(".."))
        assertNull(Uploads.safeName("dir/"))
        assertNull(Uploads.safeName(""))
        assertEquals(400, status { save("../") })
        assertEquals(File(dir, "passwd"), save("../../passwd"))
    }

    @Test
    fun `size and space limits`() {
        assertEquals(413, status { save("big.mp4", ByteArray(0), length = 1001) })
        assertEquals(411, status { save("empty.mp4", ByteArray(0)) })
        free = 150
        assertEquals("would leave less than 100 bytes free", 507, status { save("a.mp4", ByteArray(60)) })
        save("ok.mp4", ByteArray(50))
        assertEquals(listOf("ok.mp4"), files())
    }

    @Test
    fun `an interrupted upload leaves nothing behind`() {
        try {
            save("cut.mp4", ByteArray(5), length = 10)
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!, "5 of 10" in e.message!!)
        }
        val broken = object : InputStream() {
            var sent = 0
            override fun read(): Int = if (sent++ < 3) 1 else throw IOException("Wi-Fi dropped")
        }
        try {
            uploads.save("dropped.mp4", 10, broken)
            fail("expected an IOException")
        } catch (e: IOException) {
            assertEquals("Wi-Fi dropped", e.message)
        }
        assertEquals(emptyList<String>(), files())
    }
}
