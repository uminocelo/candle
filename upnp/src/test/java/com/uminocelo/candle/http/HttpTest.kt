package com.uminocelo.candle.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

private fun parse(raw: String) = HttpParser.read(ByteArrayInputStream(raw.toByteArray()))

private fun parseError(raw: String): Int = try {
    parse(raw)
    fail("expected an HttpError for: $raw")
    0
} catch (e: HttpError) {
    e.status
}

private fun request(method: String, path: String) = HttpRequest(method, path, emptyMap(), emptyMap(), ByteArray(0))

private fun HttpResponse.text() = body.toString(Charsets.UTF_8)

class HttpParserTest {
    @Test
    fun `parses a GET with a query string`() {
        val request = parse("GET /api/browse?source=usb&path=%2FMovies%20HD HTTP/1.1\r\nHost: candle\r\nX-Thing:  a b \r\n\r\n")
        assertEquals("GET", request.method)
        assertEquals("/api/browse", request.path)
        assertEquals(mapOf("source" to "usb", "path" to "/Movies HD"), request.query)
        assertEquals("candle", request.headers["host"])
        assertEquals("a b", request.headers["x-thing"])
        assertEquals(0, request.body.size)
    }

    @Test
    fun `parses a POST body and its form fields`() {
        val request = parse("POST /api/pair HTTP/1.1\r\nContent-Length: 8\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\npin=0420")
        assertEquals("POST", request.method)
        assertArrayEquals("pin=0420".toByteArray(), request.body)
        assertEquals(mapOf("pin" to "0420"), request.form())
    }

    @Test
    fun `accepts bare newlines and decodes the path`() {
        val request = parse("GET /a%20b HTTP/1.0\n\n")
        assertEquals("/a b", request.path)
    }

    @Test
    fun `reads cookies`() {
        val request = parse("GET / HTTP/1.1\r\nCookie: theme=dark; candle_token=abc=1\r\n\r\n")
        assertEquals("abc=1", request.cookie("candle_token"))
        assertNull(request.cookie("missing"))
    }

    @Test
    fun `rejects malformed requests`() {
        assertEquals(400, parseError("GARBAGE\r\n\r\n"))
        assertEquals(400, parseError("GET /x SPDY/3\r\n\r\n"))
        assertEquals(400, parseError("GET relative HTTP/1.1\r\n\r\n"))
        assertEquals(400, parseError("GET /x HTTP/1.1\r\nno colon here\r\n\r\n"))
        assertEquals(400, parseError("POST /x HTTP/1.1\r\nContent-Length: ten\r\n\r\n"))
        assertEquals(400, parseError("POST /x HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, parseError("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
        assertEquals(400, parseError("GET /x HTTP/1.1\r\nHost: cut off"))
        assertEquals(411, parseError("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
    }

    @Test
    fun `rejects oversized requests`() {
        assertEquals(413, parseError("POST /x HTTP/1.1\r\nContent-Length: ${HttpParser.MAX_BODY_BYTES + 1}\r\n\r\n"))
        val hugeHeader = "X-Big: " + "a".repeat(HttpParser.MAX_HEAD_BYTES)
        assertEquals(431, parseError("GET /x HTTP/1.1\r\n$hugeHeader\r\n\r\n"))
    }
}

class HttpResponseTest {
    @Test
    fun `writes status, length, extra headers and body`() {
        val out = ByteArrayOutputStream()
        HttpResponse.json(200, Json.obj("ok" to true)).withHeader("Set-Cookie", "a=b").writeTo(out)
        val text = out.toString("UTF-8")
        assertTrue(text, text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text, "Content-Length: 11\r\n" in text)
        assertTrue(text, "Set-Cookie: a=b\r\n" in text)
        assertTrue(text, text.endsWith("\r\n\r\n{\"ok\":true}"))
    }

    @Test
    fun `json escapes strings`() {
        assertEquals("""{"a":"say \"hi\"\\\n","n":3,"x":null}""", Json.obj("a" to "say \"hi\"\\\n", "n" to 3, "x" to null))
        assertEquals("\"\\u0001\"", Json.string("\u0001"))
    }
}

class RouterTest {
    private val router = Router().apply {
        get("/ping") { _, _ -> HttpResponse.json(200, "pong") }
        post("/api/key/{name}") { _, params -> HttpResponse.json(200, params.getValue("name")) }
        fallback { request -> if (request.path == "/index.html") HttpResponse(200, "text/html", "page".toByteArray()) else null }
    }

    @Test
    fun `routes by method and path, with parameters`() {
        assertEquals("pong", router.handle(request("GET", "/ping")).text())
        assertEquals("ok", router.handle(request("POST", "/api/key/ok")).text())
        assertEquals("ok", router.handle(request("POST", "/api/key/ok/")).text())
    }

    @Test
    fun `unknown routes are 404 and a wrong method is 405`() {
        assertEquals(404, router.handle(request("GET", "/nope")).status)
        assertEquals(404, router.handle(request("POST", "/api/key")).status)
        assertEquals(404, router.handle(request("POST", "/api/key/ok/extra")).status)
        assertEquals(405, router.handle(request("GET", "/api/key/ok")).status)
        assertEquals(405, router.handle(request("POST", "/ping")).status)
    }

    @Test
    fun `the fallback answers unmatched GETs only`() {
        assertEquals("page", router.handle(request("GET", "/index.html")).text())
        assertEquals(404, router.handle(request("POST", "/index.html")).status)
    }
}

class HttpServerTest {
    private fun exchange(port: Int, raw: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.getOutputStream().apply { write(raw.toByteArray()); flush() }
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `answers over a real socket, including errors, and keeps serving`() {
        HttpServer(0) { request ->
            if (request.path == "/boom") throw IllegalStateException("handler bug")
            HttpResponse.json(200, Json.obj("path" to request.path))
        }.use { server ->
            server.start()
            val ok = exchange(server.port, "GET /ping HTTP/1.1\r\nHost: x\r\n\r\n")
            assertTrue(ok, ok.startsWith("HTTP/1.1 200 OK") && ok.endsWith("{\"path\":\"/ping\"}"))
            val bad = exchange(server.port, "NONSENSE\r\n\r\n")
            assertTrue(bad, bad.startsWith("HTTP/1.1 400 "))
            val boom = exchange(server.port, "GET /boom HTTP/1.1\r\n\r\n")
            assertTrue(boom, boom.startsWith("HTTP/1.1 500 "))
            val again = exchange(server.port, "GET /again HTTP/1.1\r\n\r\n")
            assertTrue(again, again.endsWith("{\"path\":\"/again\"}"))
        }
    }
}
