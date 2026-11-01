package com.uminocelo.candle.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket

private fun get(path: String = "/f", range: String? = null, method: String = "GET") =
    HttpRequest(method, path, emptyMap(), if (range == null) emptyMap() else mapOf("range" to range), ByteArray(0))

private fun rangeError(header: String, size: Long): Int = try {
    ByteRange.parse(header, size)
    fail("expected 416 for $header")
    0
} catch (e: HttpError) {
    e.status
}

/** Bytes 0, 1, 2, … 255, 0, 1, … made on the fly: a "file" of any size that takes no memory. */
private class PatternStream(private var position: Long, private val end: Long) : InputStream() {
    override fun read(): Int = if (position >= end) -1 else (position++ % 256).toInt()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= end) return -1
        val n = minOf(len.toLong(), end - position).toInt()
        for (i in 0 until n) b[off + i] = ((position + i) % 256).toByte()
        position += n
        return n
    }
}

class ByteRangeTest {
    @Test
    fun `reads the three range forms`() {
        assertEquals(ByteRange(0, 99), ByteRange.parse("bytes=0-99", 1000))
        assertEquals(ByteRange(500, 999), ByteRange.parse("bytes=500-", 1000))
        assertEquals(ByteRange(900, 999), ByteRange.parse("bytes=-100", 1000))
        assertEquals("the last N bytes of a smaller file is all of it", ByteRange(0, 9), ByteRange.parse("bytes=-100", 10))
        assertEquals("an end past the file stops at its last byte", ByteRange(990, 999), ByteRange.parse("bytes=990-5000", 1000))
        assertEquals(100L, ByteRange(0, 99).length)
    }

    @Test
    fun `no header, another unit or several ranges mean the whole file`() {
        assertNull(ByteRange.parse(null, 1000))
        assertNull(ByteRange.parse("items=0-1", 1000))
        assertNull(ByteRange.parse("bytes=0-1,5-6", 1000))
    }

    @Test
    fun `impossible or malformed ranges are 416`() {
        assertEquals(416, rangeError("bytes=1000-", 1000))
        assertEquals(416, rangeError("bytes=5-2", 1000))
        assertEquals(416, rangeError("bytes=-0", 1000))
        assertEquals(416, rangeError("bytes=-", 1000))
        assertEquals(416, rangeError("bytes=abc", 1000))
        assertEquals(416, rangeError("bytes=0-", 0))
        assertEquals(416, rangeError("bytes=99999999999999999999-", 1000))
    }
}

class RangedResponseTest {
    private val size = 1000L
    private fun respond(range: String?) =
        HttpResponse.ranged(get(range = range), size, "video/mp4") { offset -> PatternStream(offset, size) }

    private fun HttpResponse.bytes(): ByteArray = ByteArrayOutputStream().also { writeTo(it) }.toByteArray().let { all ->
        val headEnd = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        all.copyOfRange(headEnd, all.size)
    }

    @Test
    fun `whole file without a range, with Accept-Ranges`() {
        val response = respond(null)
        assertEquals(200, response.status)
        assertEquals("bytes", response.headers["Accept-Ranges"])
        assertEquals(size, response.stream!!.length)
        assertArrayEquals(ByteArray(1000) { (it % 256).toByte() }, response.bytes())
    }

    @Test
    fun `one range is 206 with Content-Range and exactly those bytes`() {
        val response = respond("bytes=300-303")
        assertEquals(206, response.status)
        assertEquals("bytes 300-303/1000", response.headers["Content-Range"])
        assertArrayEquals(byteArrayOf(44, 45, 46, 47), response.bytes()) // 300 % 256 = 44
    }

    @Test
    fun `an impossible range is 416 with the size`() {
        val response = respond("bytes=2000-")
        assertEquals(416, response.status)
        assertEquals("bytes */1000", response.headers["Content-Range"])
    }

    @Test
    fun `files seek to the range start`() {
        val file = File.createTempFile("candle", ".bin").apply {
            deleteOnExit()
            writeBytes(ByteArray(5000) { (it % 251).toByte() })
        }
        val response = HttpResponse.file(get(range = "bytes=-3"), file, "application/octet-stream")
        assertEquals("bytes 4997-4999/5000", response.headers["Content-Range"])
        assertArrayEquals(byteArrayOf((4997 % 251).toByte(), (4998 % 251).toByte(), (4999 % 251).toByte()), response.bytes())
    }

    @Test
    fun `HEAD runs the GET route`() {
        val router = Router().apply { get("/f") { _, _ -> HttpResponse.json(200, "{}") } }
        assertEquals(200, router.handle(get(method = "HEAD")).status)
    }
}

class StreamingServerTest {
    /** Sends [raw] and returns the response head and how many body bytes followed, without keeping the body. */
    private fun exchange(port: Int, raw: String): Pair<String, Long> = Socket("127.0.0.1", port).use { socket ->
        socket.getOutputStream().apply { write(raw.toByteArray()); flush() }
        val input = socket.getInputStream().buffered()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { check(it >= 0) }.toChar())
        var count = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            count += n
        }
        head.toString() to count
    }

    @Test
    fun `streams a 2 GB resource with a small heap, serves a range, and answers HEAD without a body`() {
        val size = 2L * 1024 * 1024 * 1024
        HttpServer(0, maxConnections = 8) { request ->
            HttpResponse.ranged(request, size, "video/mp4") { offset -> PatternStream(offset, size) }
        }.use { server ->
            server.start()
            val (fromEnd, tail) = exchange(server.port, "GET /film HTTP/1.1\r\nRange: bytes=-1048576\r\n\r\n")
            assertTrue(fromEnd, fromEnd.startsWith("HTTP/1.1 206 Partial Content\r\n"))
            assertTrue(fromEnd, "Content-Range: bytes ${size - 1048576}-${size - 1}/$size\r\n" in fromEnd)
            assertEquals(1048576L, tail)

            val (head, bodyBytes) = exchange(server.port, "HEAD /film HTTP/1.1\r\n\r\n")
            assertTrue(head, head.startsWith("HTTP/1.1 200 OK\r\n") && "Content-Length: $size\r\n" in head)
            assertEquals(0L, bodyBytes)

            // The whole 2 GB goes through a 64 KB buffer: nothing close to its size is ever held.
            val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            val (whole, all) = exchange(server.port, "GET /film HTTP/1.1\r\n\r\n")
            val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            assertTrue(whole, "Content-Length: $size\r\n" in whole)
            assertEquals(size, all)
            assertTrue("heap grew by ${(after - before) / 1_000_000} MB", after - before < 256L * 1024 * 1024)
        }
    }
}
