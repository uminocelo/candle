package com.uminocelo.candle.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val UUID = "1b4f9c2e-0000-4000-8000-candle000001"
private const val MR = "urn:schemas-upnp-org:device:MediaRenderer:1"
private const val SERVER = "Test/1.0 UPnP/1.0 Candle/1.0"

/** A renderer with the three services Candle's has. */
private val device = SsdpDevice(
    UUID, MR,
    listOf("urn:schemas-upnp-org:service:AVTransport:1", "urn:schemas-upnp-org:service:RenderingControl:1", "urn:schemas-upnp-org:service:ConnectionManager:1"),
    SERVER,
)

class SsdpTest {
    private val location = "http://192.0.2.215:8765/dlna/device.xml"

    @Test
    fun `reads the search target of an M-SEARCH only`() {
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ${MR}\r\n\r\n"
        assertEquals(MR, Ssdp.searchTarget(search))
        assertEquals("ssdp:all", Ssdp.searchTarget("m-search * http/1.1\nman: ssdp:discover\nst: ssdp:all\n\n"))
        assertNull(Ssdp.searchTarget("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\n\r\n"))
        assertNull(Ssdp.searchTarget("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n"))
        assertNull(Ssdp.searchTarget("garbage"))
    }

    @Test
    fun `answers ssdp-all with every target and a specific search with one`() {
        assertEquals(6, Ssdp.responses("ssdp:all", device, location).size)
        val renderer = Ssdp.responses(MR, device, location).single()
        assertTrue(renderer, renderer.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(renderer, "LOCATION: $location\r\n" in renderer)
        assertTrue(renderer, "USN: uuid:$UUID::${MR}\r\n" in renderer)
        assertEquals(1, Ssdp.responses("uuid:$UUID", device, location).size)
        assertTrue(Ssdp.responses("urn:schemas-upnp-org:device:MediaServer:1", device, location).isEmpty())
    }

    @Test
    fun `alive and replies carry the device's SERVER header`() {
        assertTrue(SERVER in Ssdp.notify("upnp:rootdevice", "u", location, alive = true, server = SERVER))
        assertTrue(Ssdp.responses(MR, device, location).single().contains("SERVER: $SERVER\r\n"))
    }

    @Test
    fun `a MediaServer announces its own type and services`() {
        val server = SsdpDevice(UUID, "urn:schemas-upnp-org:device:MediaServer:1", listOf("urn:schemas-upnp-org:service:ContentDirectory:1"), SERVER)
        assertEquals(4, Ssdp.targets(server).size)
        assertEquals(1, Ssdp.responses("urn:schemas-upnp-org:service:ContentDirectory:1", server, location).size)
        assertTrue(Ssdp.responses(MR, server, location).isEmpty())
    }

    @Test
    fun `byebye carries no location`() {
        val bye = Ssdp.notify("upnp:rootdevice", "uuid:$UUID::upnp:rootdevice", location, alive = false, server = SERVER)
        assertTrue(bye, "NTS: ssdp:byebye\r\n" in bye)
        assertTrue(bye, "LOCATION" !in bye)
        assertTrue("NTS: ssdp:alive\r\n" in Ssdp.notify("upnp:rootdevice", "u", location, alive = true, server = SERVER))
    }
}
