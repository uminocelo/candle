package com.uminocelo.candle.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

class GenaTest {
    @Test
    fun `reads callback URLs and timeouts`() {
        assertEquals(
            listOf(URI("http://192.0.2.50:49152/cb"), URI("http://phone/x?y=1")),
            Gena.callbacks("<http://192.0.2.50:49152/cb><ftp://nope/><http://phone/x?y=1>"),
        )
        assertTrue(Gena.callbacks(null).isEmpty())
        assertTrue(Gena.callbacks("http://no-brackets/").isEmpty())
        assertEquals(300L, Gena.timeout("Second-300"))
        assertEquals(Subscriptions.MAX_SECONDS, Gena.timeout("infinite"))
        assertEquals(Subscriptions.MAX_SECONDS, Gena.timeout(null))
    }

    @Test
    fun `subscriptions expire, renew, unsubscribe and are capped`() {
        var now = 0L
        val subs = Subscriptions { now }
        val a = subs.subscribe(listOf(URI("http://a/")), timeoutSeconds = 5) // granted the minimum, 60 s
        assertEquals(Subscriptions.MIN_SECONDS * 1000, a.expiresAt)
        now = 59_000
        assertEquals(a, subs.renew(a.sid, 120))
        now = 170_000
        assertEquals(listOf(a), subs.current())
        now = 179_000
        assertTrue("expired subscriptions are dropped", subs.current().isEmpty())
        assertNull(subs.renew(a.sid, 120))
        assertFalse(subs.unsubscribe(a.sid))

        val b = subs.subscribe(listOf(URI("http://b/")), 300)
        assertTrue(subs.unsubscribe(b.sid))
        assertTrue(subs.current().isEmpty())

        val first = subs.subscribe(listOf(URI("http://c/")), 300)
        repeat(Subscriptions.MAX) { subs.subscribe(listOf(URI("http://d$it/")), 300) }
        assertEquals(Subscriptions.MAX, subs.current().size)
        assertFalse("the oldest goes first", first in subs.current())
    }

    @Test
    fun `SEQ starts at 0 and wraps to 1`() {
        val s = Subscription("uuid:x", emptyList(), 0)
        assertEquals(listOf(0L, 1L, 2L), List(3) { s.nextSeq() })
        s.seq = Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, s.nextSeq())
        assertEquals(1L, s.nextSeq())
    }

    @Test
    fun `NOTIFY carries the GENA headers and reaches a real socket`() {
        val request = Gena.notifyRequest(URI("http://phone:5000/cb?id=7"), "uuid:abc", 3, "<x/>").toString(Charsets.UTF_8)
        assertTrue(request, request.startsWith("NOTIFY /cb?id=7 HTTP/1.1\r\nHOST: phone:5000\r\n"))
        for (header in listOf("NT: upnp:event", "NTS: upnp:propchange", "SID: uuid:abc", "SEQ: 3", "CONTENT-LENGTH: 4")) {
            assertTrue(header, "$header\r\n" in request)
        }
        assertTrue(request.endsWith("\r\n\r\n<x/>"))

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            var received = ""
            val reader = Thread {
                server.accept().use {
                    received = it.getInputStream().bufferedReader().readLine()
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }.apply { start() }
            assertTrue(Gena.send(URI("http://127.0.0.1:${server.localPort}/cb"), "uuid:abc", 0, "<x/>"))
            reader.join(2000)
            assertEquals("NOTIFY /cb HTTP/1.1", received)
        }
        assertFalse("a closed port fails", Gena.send(URI("http://127.0.0.1:1/cb"), "uuid:abc", 0, "<x/>", timeoutMs = 500))
    }
}
