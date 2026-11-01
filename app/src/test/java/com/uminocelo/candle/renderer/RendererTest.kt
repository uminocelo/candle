package com.uminocelo.candle.renderer

import com.uminocelo.candle.dlna.Upnp
import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val UUID = "1b4f9c2e-0000-4000-8000-candle000001"

private fun envelope(service: String, action: String, args: String) =
    """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""

private const val METADATA = """&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;&lt;item id="1" parentID="0" restricted="1"&gt;&lt;dc:title&gt;Holiday &amp;amp; Friends&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;&lt;res protocolInfo="http-get:*:video/mp4:*"&gt;http://phone:8080/v.mp4&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"""

class DidlTitleTest {
    @Test
    fun `reads the title from CurrentURIMetaData`() {
        val didl = METADATA.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        assertEquals("Holiday & Friends", Upnp.itemTitle(didl))
        assertNull(Upnp.itemTitle(""))
        assertNull(Upnp.itemTitle("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"/>"))
    }

    @Test(expected = IOException::class)
    fun `malformed metadata throws`() {
        Upnp.itemTitle("<DIDL-Lite><item>")
    }
}

class RendererDescriptionTest {
    @Test
    fun `the device description names Candle and lists the three services`() {
        val doc = Upnp.parse(RendererDescription.device(UUID, "Candle & co"))
        val text = { name: String -> doc.getElementsByTagNameNS("*", name).item(0).textContent }
        assertEquals(RendererDescription.MEDIA_RENDERER, text("deviceType"))
        assertEquals("Candle & co", text("friendlyName"))
        assertEquals("uuid:$UUID", text("UDN"))
        val types = doc.getElementsByTagNameNS("*", "serviceType").let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent } }
        assertEquals(RendererDescription.SERVICES.values.toList(), types)
    }

    @Test
    fun `every service has a parseable SCPD and unknown ones have none`() {
        for (service in RendererDescription.SERVICES.keys) {
            val scpd = Upnp.parse(RendererDescription.scpd(service)!!)
            assertEquals(service, "scpd", scpd.documentElement.localName)
        }
        val actions = Upnp.parse(RendererDescription.scpd("AVTransport")!!).getElementsByTagNameNS("*", "action")
        assertEquals(11, actions.length)
        assertNull(RendererDescription.scpd("Nope"))
    }
}

class MediaRendererTest {
    private val fake = FakeRendererPlayer { 0L }
    private val played get() = fake.played
    private val errors get() = fake.errors
    private val deadUrls = mutableSetOf<String>()
    private val renderer = MediaRenderer(UUID, fake, { 0L }, check = { url -> if (url in deadUrls) "HTTP 404" else null })

    private fun control(service: String, action: String, args: String, serviceType: String = RendererDescription.SERVICES.getValue(service)): HttpResponse =
        renderer.handle(
            HttpRequest(
                "POST", "/dlna/control/$service", emptyMap(),
                mapOf("soapaction" to "\"$serviceType#$action\""), envelope(serviceType, action, args).toByteArray(),
            ),
        )

    private fun HttpResponse.errorCode(): String? =
        Upnp.parse(body.toString(Charsets.UTF_8)).getElementsByTagNameNS("*", "errorCode").item(0)?.textContent

    private fun setUri(url: String, metadata: String = "", instance: String = "0") =
        control("AVTransport", "SetAVTransportURI", "<InstanceID>$instance</InstanceID><CurrentURI>$url</CurrentURI><CurrentURIMetaData>$metadata</CurrentURIMetaData>")

    private fun play() = control("AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    @Test
    fun `SetAVTransportURI then Play opens the URL with the title from the metadata`() {
        assertEquals(200, setUri("http://phone:8080/v.mp4", METADATA).status)
        assertTrue(played.isEmpty())
        assertEquals(200, play().status)
        assertEquals(listOf("http://phone:8080/v.mp4" to "Holiday & Friends"), played)
    }

    @Test
    fun `without metadata, or with broken metadata, the title comes from the URL`() {
        setUri("https://cdn.example/films/My%20Film.mkv?token=1")
        play()
        setUri("http://phone/clip.webm", "&lt;DIDL-Lite&gt;&lt;item&gt;")
        play()
        assertEquals(listOf("My Film", "clip"), played.map { it.second })
    }

    @Test
    fun `bad requests get UPnP errors and play nothing`() {
        assertEquals("701", play().errorCode())
        assertEquals("716", setUri("file:///sdcard/secret.mp4").errorCode())
        assertEquals("716", setUri("not a url").errorCode())
        assertEquals("718", setUri("http://phone/v.mp4", instance = "3").errorCode())
        assertEquals("402", control("AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID>").errorCode())
        assertEquals("401", control("AVTransport", "Record", "<InstanceID>0</InstanceID>").errorCode())
        assertEquals("401", control("AVTransport", "Play", "<InstanceID>0</InstanceID>", serviceType = RendererDescription.RENDERING_CONTROL).errorCode())
        val malformed = renderer.handle(HttpRequest("POST", "/dlna/control/AVTransport", emptyMap(), emptyMap(), "<oops".toByteArray()))
        assertEquals(500, malformed.status)
        assertEquals("401", malformed.errorCode())
        assertTrue(played.isEmpty())
    }

    @Test
    fun `a URL that does not answer is error 716, shown on the projector, and keeps the previous video`() {
        setUri("http://phone/good.mp4")
        deadUrls += "http://phone/gone.mp4"
        assertEquals("716", setUri("http://phone/gone.mp4").errorCode())
        assertEquals(listOf("gone" to "HTTP 404"), errors)
        play()
        assertEquals(listOf("http://phone/good.mp4" to "good"), played)
    }

    @Test
    fun `ConnectionManager reports what Candle can play`() {
        val response = control("ConnectionManager", "GetProtocolInfo", "")
        assertEquals(200, response.status)
        val sink = Upnp.parse(response.body.toString(Charsets.UTF_8)).getElementsByTagNameNS("*", "Sink").item(0).textContent
        assertTrue(sink, "http-get:*:video/mp4:*" in sink.split(','))
        assertEquals(200, control("ConnectionManager", "GetCurrentConnectionIDs", "").status)
    }

    @Test
    fun `serves the descriptions and 404s the rest`() {
        val get = { path: String -> renderer.handle(HttpRequest("GET", path, emptyMap(), emptyMap(), ByteArray(0))) }
        assertTrue(get("/dlna/device.xml").body.toString(Charsets.UTF_8).contains("uuid:$UUID"))
        assertEquals(200, get("/dlna/AVTransport.xml").status)
        assertEquals(404, get("/dlna/Nope.xml").status)
        assertEquals(404, control("Nope", "Play", "", serviceType = RendererDescription.AV_TRANSPORT).status)
    }
}

class UrlCheckTest {
    private val requests = mutableListOf<String>()

    /** Answers each connection: /ok.mp4 → 206, /slow.mp4 after 1.5 s, anything else 404. */
    private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also { socket ->
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                Thread {
                    client.use {
                        val head = it.getInputStream().bufferedReader().lineSequence().takeWhile { line -> line.isNotEmpty() }.toList()
                        synchronized(requests) { requests += head.joinToString("\n") }
                        val path = head.first().split(' ')[1]
                        if (path == "/slow.mp4") Thread.sleep(1500)
                        val status = if (path == "/missing.mp4") "404 Not Found" else "206 Partial Content"
                        it.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }
    private val base = "http://127.0.0.1:${server.localPort}"

    @org.junit.After
    fun stop() = server.close()

    @Test
    fun `an answering URL passes, a missing file or closed port fails, a slow server passes`() {
        assertNull(checkUrl("$base/ok.mp4"))
        assertTrue(requests.single(), "Range: bytes=0-0" in requests.single())
        assertEquals("HTTP 404", checkUrl("$base/missing.mp4"))
        assertTrue(checkUrl("http://127.0.0.1:1/x.mp4") != null)
        assertNull(checkUrl("$base/slow.mp4", timeoutMs = 300))
    }
}
