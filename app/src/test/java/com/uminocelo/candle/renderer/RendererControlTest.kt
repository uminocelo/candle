package com.uminocelo.candle.renderer

import com.uminocelo.candle.dlna.Upnp
import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import com.uminocelo.candle.player.PlaybackKind
import com.uminocelo.candle.player.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.net.URI

private const val URL = "http://phone:8080/v.mp4"

class RendererStateTest {
    private fun snapshot(url: String, playWhenReady: Boolean) =
        PlaybackSnapshot(PlaybackKind.VIDEO, "V", playing = playWhenReady, positionMs = 0, durationMs = 0, reportedAt = 0, uri = url)

    @Test
    fun `maps the player's report to the transport state`() {
        assertEquals(TransportState.NO_MEDIA_PRESENT, RendererState.transportState(null, snapshot(URL, true), null, 0))
        assertEquals(TransportState.PLAYING, RendererState.transportState(URL, snapshot(URL, true), null, 0))
        assertEquals(TransportState.PAUSED_PLAYBACK, RendererState.transportState(URL, snapshot(URL, false), null, 0))
        assertEquals(TransportState.STOPPED, RendererState.transportState(URL, null, null, 0))
        // Another video on screen (a USB file) is not this renderer's.
        assertEquals(TransportState.STOPPED, RendererState.transportState(URL, snapshot("file:///usb/a.mkv", true), null, 0))
    }

    @Test
    fun `after Play the state is TRANSITIONING until the player reports, then STOPPED if it never does`() {
        assertEquals(TransportState.TRANSITIONING, RendererState.transportState(URL, null, playRequestedAt = 1_000, now = 5_000))
        assertEquals(TransportState.STOPPED, RendererState.transportState(URL, null, playRequestedAt = 1_000, now = 1_000 + RendererState.OPENING_MS))
        assertEquals(TransportState.PLAYING, RendererState.transportState(URL, snapshot(URL, true), playRequestedAt = 1_000, now = 1_500))
    }

    @Test
    fun `buffering after a seek still counts as playing`() {
        val buffering = snapshot(URL, true).copy(playing = false)
        assertEquals(TransportState.PLAYING, RendererState.transportState(URL, buffering, null, 0))
    }

    @Test
    fun `formats and parses UPnP times`() {
        assertEquals("0:00:00", RendererState.formatTime(0))
        assertEquals("0:01:23", RendererState.formatTime(83_999))
        assertEquals("2:03:04", RendererState.formatTime((2 * 3600 + 3 * 60 + 4) * 1000L))
        assertEquals("0:00:00", RendererState.formatTime(-5))
        assertEquals(83_000L, RendererState.parseTime("0:01:23"))
        assertEquals(83_500L, RendererState.parseTime("00:01:23.5"))
        assertEquals(83_250L, RendererState.parseTime("01:23.250"))
        assertEquals(7_384_000L, RendererState.parseTime("2:03:04"))
        assertNull(RendererState.parseTime("1:75:00"))
        assertNull(RendererState.parseTime("soon"))
        assertNull(RendererState.parseTime(""))
    }

    @Test
    fun `LastChange is an Event for instance 0, escaped once inside and once in the property`() {
        val inner = RendererState.lastChange(RendererState.AVT_EVENT_NS, linkedMapOf("TransportState" to "PLAYING", "AVTransportURI" to "http://a/?x=1&y=\"2\""))
        val body = Upnp.parse(RendererState.propertySet(mapOf("LastChange" to inner)))
        assertEquals("propertyset", body.documentElement.localName)
        val lastChange = body.getElementsByTagName("LastChange").item(0).textContent
        assertEquals(inner, lastChange)
        val event = Upnp.parse(lastChange).documentElement
        assertEquals(RendererState.AVT_EVENT_NS, event.namespaceURI)
        val instance = event.firstChild as Element
        assertEquals("0", instance.getAttribute("val"))
        assertEquals("PLAYING", (instance.getElementsByTagName("TransportState").item(0) as Element).getAttribute("val"))
        assertEquals("http://a/?x=1&y=\"2\"", (instance.getElementsByTagName("AVTransportURI").item(0) as Element).getAttribute("val"))
    }

    @Test
    fun `RenderingControl variables carry the Master channel`() {
        val event = Upnp.parse(RendererState.lastChange(RendererState.RCS_EVENT_NS, linkedMapOf("Volume" to "40", "Mute" to "0")))
        val volume = event.getElementsByTagName("Volume").item(0) as Element
        assertEquals("Master", volume.getAttribute("channel"))
        assertEquals("40", volume.getAttribute("val"))
    }
}

class RendererControlTest {
    private var now = 1_000L
    private val fake = FakeRendererPlayer { now }
    private val sent = mutableListOf<Triple<String, Long, String>>()
    private val renderer = MediaRenderer(UUID_FOR_TESTS, fake, { now }, check = { null }) { callback, sid, seq, body ->
        sent += Triple("$callback $sid", seq, body)
        true
    }

    private fun control(service: String, action: String, args: String = ""): HttpResponse {
        val type = RendererDescription.SERVICES.getValue(service)
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:$action xmlns:u="$type"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
        return renderer.handle(HttpRequest("POST", "/dlna/control/$service", emptyMap(), mapOf("soapaction" to "\"$type#$action\""), body.toByteArray()))
    }

    private fun HttpResponse.out(name: String): String? =
        Upnp.parse(body.toString(Charsets.UTF_8)).getElementsByTagName(name).item(0)?.textContent

    private fun HttpResponse.errorCode(): String? =
        Upnp.parse(body.toString(Charsets.UTF_8)).getElementsByTagNameNS("*", "errorCode").item(0)?.textContent

    private fun state() = control("AVTransport", "GetTransportInfo").out("CurrentTransportState")

    private fun start(url: String = URL) {
        control("AVTransport", "SetAVTransportURI", "<CurrentURI>$url</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        control("AVTransport", "Play", "<Speed>1</Speed>")
    }

    private fun request(method: String, service: String, headers: Map<String, String>) =
        renderer.handle(HttpRequest(method, "/dlna/event/$service", emptyMap(), headers, ByteArray(0)))

    private fun subscribe(service: String, callback: String = "http://phone:5000/$service", timeout: String = "Second-300") =
        request("SUBSCRIBE", service, mapOf("callback" to "<$callback>", "nt" to "upnp:event", "timeout" to timeout))

    /** The LastChange variables of a sent event, name → val. */
    private fun lastChangeVars(body: String): Map<String, String> {
        val inner = Upnp.parse(body).getElementsByTagName("LastChange").item(0).textContent
        val instance = Upnp.parse(inner).documentElement.firstChild as Element
        return (0 until instance.childNodes.length).map { instance.childNodes.item(it) as Element }.associate { it.tagName to it.getAttribute("val") }
    }

    @Test
    fun `walks through NO_MEDIA_PRESENT, STOPPED, PLAYING, PAUSED_PLAYBACK and back to STOPPED`() {
        assertEquals("NO_MEDIA_PRESENT", state())
        control("AVTransport", "SetAVTransportURI", "<CurrentURI>$URL</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        assertEquals("STOPPED", state())
        control("AVTransport", "Play", "<Speed>1</Speed>")
        assertEquals("PLAYING", state())
        assertEquals(200, control("AVTransport", "Pause").status)
        assertEquals("PAUSED_PLAYBACK", state())
        control("AVTransport", "Play", "<Speed>1</Speed>")
        assertEquals("PLAYING", state())
        assertEquals("the player resumed rather than reopening", 1, fake.played.size)
        assertEquals(200, control("AVTransport", "Stop").status)
        assertEquals("STOPPED", state())
        control("AVTransport", "Play", "<Speed>1</Speed>")
        assertEquals("Play after Stop opens it again", 2, fake.played.size)
    }

    @Test
    fun `a slow player is TRANSITIONING and a second Play does not open it twice`() {
        fake.reportsOnOpen = false
        start()
        assertEquals("TRANSITIONING", state())
        control("AVTransport", "Play", "<Speed>1</Speed>")
        assertEquals(1, fake.played.size)
        now += RendererState.OPENING_MS
        assertEquals("STOPPED", state())
    }

    @Test
    fun `a new URL while playing switches at once`() {
        start()
        control("AVTransport", "SetAVTransportURI", "<CurrentURI>http://phone/next.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        assertEquals(listOf(URL, "http://phone/next.mp4"), fake.played.map { it.first })
        assertEquals("PLAYING", state())
    }

    @Test
    fun `position follows the player and seek moves it`() {
        start()
        now += 40_500
        val info = control("AVTransport", "GetPositionInfo")
        assertEquals("0:00:40", info.out("RelTime"))
        assertEquals("0:01:00", info.out("TrackDuration"))
        assertEquals(URL, info.out("TrackURI"))
        assertEquals("1", info.out("Track"))

        assertEquals(200, control("AVTransport", "Seek", "<Unit>REL_TIME</Unit><Target>0:00:30</Target>").status)
        assertEquals(listOf(30_000L), fake.seeks)
        control("AVTransport", "Seek", "<Unit>ABS_TIME</Unit><Target>9:00:00</Target>")
        assertEquals("a seek past the end stops at the end", fake.durationMs, fake.seeks.last())
    }

    @Test
    fun `transport errors`() {
        assertEquals("701", control("AVTransport", "Pause").errorCode())
        assertEquals("701", control("AVTransport", "Seek", "<Unit>REL_TIME</Unit><Target>0:00:10</Target>").errorCode())
        start()
        assertEquals("710", control("AVTransport", "Seek", "<Unit>TRACK_NR</Unit><Target>1</Target>").errorCode())
        assertEquals("711", control("AVTransport", "Seek", "<Unit>REL_TIME</Unit><Target>later</Target>").errorCode())
        assertEquals("402", control("AVTransport", "Seek", "<Unit>REL_TIME</Unit>").errorCode())
        // A USB video on screen is not the renderer's to pause.
        fake.report("file:///usb/a.mkv", playing = true, positionMs = 0)
        assertEquals("701", control("AVTransport", "Pause").errorCode())
    }

    @Test
    fun `media info and transport actions`() {
        assertEquals("0", control("AVTransport", "GetMediaInfo").out("NrTracks"))
        start()
        val media = control("AVTransport", "GetMediaInfo")
        assertEquals("1", media.out("NrTracks"))
        assertEquals(URL, media.out("CurrentURI"))
        assertEquals("Pause,Stop,Seek", control("AVTransport", "GetCurrentTransportActions").out("Actions"))
        assertEquals("NETWORK", control("AVTransport", "GetDeviceCapabilities").out("PlayMedia"))
        assertEquals("NORMAL", control("AVTransport", "GetTransportSettings").out("PlayMode"))
    }

    @Test
    fun `volume and mute`() {
        val channel = "<Channel>Master</Channel>"
        assertEquals("40", control("RenderingControl", "GetVolume", channel).out("CurrentVolume"))
        assertEquals(200, control("RenderingControl", "SetVolume", "$channel<DesiredVolume>65</DesiredVolume>").status)
        assertEquals(65, fake.volumePercent)
        assertEquals("402", control("RenderingControl", "SetVolume", "$channel<DesiredVolume>101</DesiredVolume>").errorCode())
        assertEquals("402", control("RenderingControl", "SetVolume", "$channel<DesiredVolume>loud</DesiredVolume>").errorCode())
        assertEquals("0", control("RenderingControl", "GetMute", channel).out("CurrentMute"))
        control("RenderingControl", "SetMute", "$channel<DesiredMute>true</DesiredMute>")
        assertEquals("1", control("RenderingControl", "GetMute", channel).out("CurrentMute"))
        control("RenderingControl", "SetMute", "$channel<DesiredMute>0</DesiredMute>")
        assertFalse(fake.mutedNow)
        assertEquals("402", control("RenderingControl", "SetMute", "$channel<DesiredMute>maybe</DesiredMute>").errorCode())
    }

    @Test
    fun `SUBSCRIBE answers with a SID and the granted timeout, and checks its headers`() {
        val ok = subscribe("AVTransport", timeout = "Second-100000")
        assertEquals(200, ok.status)
        assertTrue(ok.headers.getValue("SID").startsWith("uuid:"))
        assertEquals("Second-1800", ok.headers["TIMEOUT"])
        val sid = ok.headers.getValue("SID")
        assertEquals(200, request("SUBSCRIBE", "AVTransport", mapOf("sid" to sid, "timeout" to "Second-300")).status)
        assertEquals(400, request("SUBSCRIBE", "AVTransport", mapOf("sid" to sid, "nt" to "upnp:event")).status)
        assertEquals(412, request("SUBSCRIBE", "AVTransport", mapOf("sid" to "uuid:nope")).status)
        assertEquals(412, request("SUBSCRIBE", "AVTransport", mapOf("callback" to "<http://phone/>")).status)
        assertEquals(412, request("SUBSCRIBE", "AVTransport", mapOf("nt" to "upnp:event")).status)
        assertEquals(404, subscribe("Nope").status)
        assertEquals(200, request("UNSUBSCRIBE", "AVTransport", mapOf("sid" to sid)).status)
        assertEquals(412, request("UNSUBSCRIBE", "AVTransport", mapOf("sid" to sid)).status)
    }

    @Test
    fun `a new subscriber gets the full state, then only changes`() {
        subscribe("AVTransport")
        renderer.tick()
        val first = sent.single()
        assertEquals(0L, first.second)
        val full = lastChangeVars(first.third)
        assertEquals("NO_MEDIA_PRESENT", full["TransportState"])
        assertTrue(full.keys.containsAll(listOf("TransportStatus", "AVTransportURI", "CurrentTransportActions", "CurrentTrackDuration")))

        renderer.tick()
        assertEquals("nothing changed, nothing sent", 1, sent.size)

        start()
        renderer.tick()
        val change = lastChangeVars(sent.last().third)
        assertEquals(1L, sent.last().second)
        assertEquals("PLAYING", change["TransportState"])
        assertEquals(URL, change["AVTransportURI"])
        assertFalse("unchanged variables stay out", "TransportStatus" in change)

        // Paused with the projector's remote: the next tick tells the phone.
        fake.pause()
        renderer.tick()
        assertEquals(mapOf("TransportState" to "PAUSED_PLAYBACK", "CurrentTransportActions" to "Play,Stop,Seek"), lastChangeVars(sent.last().third))
    }

    @Test
    fun `volume changes are evented and ConnectionManager sends its variables once`() {
        subscribe("RenderingControl")
        subscribe("ConnectionManager")
        renderer.tick()
        assertEquals(2, sent.size)
        assertEquals(mapOf("Volume" to "40", "Mute" to "0"), lastChangeVars(sent.first { "RenderingControl" in it.first }.third))
        val cm = Upnp.parse(sent.first { "ConnectionManager" in it.first }.third)
        assertTrue(cm.getElementsByTagName("SinkProtocolInfo").item(0).textContent.contains("video/mp4"))

        fake.volumePercent = 20
        renderer.tick()
        assertEquals(3, sent.size)
        assertEquals(mapOf("Volume" to "20"), lastChangeVars(sent.last().third))
    }

    @Test
    fun `expired and switched-off subscriptions get nothing`() {
        subscribe("AVTransport", timeout = "Second-60")
        renderer.tick()
        now += 61_000
        start()
        renderer.tick()
        assertEquals(1, sent.size)

        subscribe("AVTransport")
        renderer.clearSubscriptions()
        renderer.tick()
        assertEquals(1, sent.size)
    }

    @Test
    fun `an event goes to the next callback when the first one fails`() {
        val tried = mutableListOf<String>()
        val picky = MediaRenderer(UUID_FOR_TESTS, fake, { now }) { callback, _, _, _ ->
            tried += callback.toString()
            callback.host == "second"
        }
        picky.handle(HttpRequest("SUBSCRIBE", "/dlna/event/AVTransport", emptyMap(), mapOf("callback" to "<http://first/><http://second/>", "nt" to "upnp:event"), ByteArray(0)))
        picky.tick()
        assertEquals(listOf("http://first/", "http://second/"), tried)
    }

    private companion object {
        const val UUID_FOR_TESTS = "1b4f9c2e-0000-4000-8000-candle000002"
    }
}
