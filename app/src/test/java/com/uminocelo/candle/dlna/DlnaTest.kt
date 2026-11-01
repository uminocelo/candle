package com.uminocelo.candle.dlna

import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaStream
import com.uminocelo.candle.media.MediaType
import com.uminocelo.candle.player.PlaybackTarget
import com.uminocelo.candle.resume.ResumeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private fun didl(body: String) = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
 xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">$body</DIDL-Lite>"""

/** A SOAP BrowseResponse carrying [didlXml] escaped inside Result, as servers send it. */
private fun browseResponse(didlXml: String, returned: Int, total: Int) = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
<Result>${didlXml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</Result>
<NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches><UpdateID>1</UpdateID>
</u:BrowseResponse></s:Body></s:Envelope>"""

class DidlParsingTest {
    @Test
    fun `containers become folders`() {
        val entries = Upnp.didl(
            didl(
                """<container id="64" parentID="0" restricted="1" childCount="3">
                <dc:title>Video</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>""",
            ),
        )
        assertEquals(listOf(DidlEntry("64", "Video", MediaType.FOLDER, null, null)), entries)
    }

    @Test
    fun `items take the original resource matching their type, not a transcode or thumbnail`() {
        val entries = Upnp.didl(
            didl(
                """<item id="64$1" parentID="64" restricted="1">
                <dc:title>Holiday &amp; Friends</dc:title><upnp:class>object.item.videoItem.movie</upnp:class>
                <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://10.0.0.2:8200/Thumb/1.jpg</res>
                <res size="38123456" duration="0:01:45.000" protocolInfo="http-get:*:video/mp4:*">http://10.0.0.2:8200/MediaItems/1.mp4</res>
                <res protocolInfo="http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS">http://10.0.0.2:8200/Transcode/1.ts</res>
                <res protocolInfo="rtsp-rtp-udp:*:video/mp4:*">rtsp://10.0.0.2/1</res>
                </item>""",
            ),
        )
        assertEquals(
            listOf(DidlEntry("64$1", "Holiday & Friends", MediaType.VIDEO, "http://10.0.0.2:8200/MediaItems/1.mp4", 38123456)),
            entries,
        )
    }

    @Test
    fun `items with no matching MIME type fall back to the first HTTP resource`() {
        val entries = Upnp.didl(
            didl(
                """<item id="a1"><dc:title>Song</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>
                <res protocolInfo="http-get:*:application/octet-stream:*">http://s/a1</res></item>""",
            ),
        )
        assertEquals(DidlEntry("a1", "Song", MediaType.AUDIO, "http://s/a1", null), entries.single())
    }

    @Test
    fun `items without an HTTP resource have no URL`() {
        val entries = Upnp.didl(
            didl(
                """<item id="p1"><dc:title>Photo</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>
                <res protocolInfo="http-get:*:image/jpeg:*">http://s/p1.jpg</res></item>
                <item id="r1"><dc:title>Stream</dc:title><upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="rtsp-rtp-udp:*:video/mp4:*">rtsp://s/r1</res></item>""",
            ),
        )
        assertEquals(DidlEntry("r1", "Stream", MediaType.VIDEO, null, null), entries[1])
    }

    @Test
    fun `photos open their full image, not the thumbnail, and text items are documents`() {
        val entries = Upnp.didl(
            didl(
                """<item id="p1"><dc:title>beach.jpg</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>
                <res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN">http://s/thumb/p1</res>
                <res protocolInfo="http-get:*:image/jpeg:*" size="3000000">http://s/media/p1.jpg</res></item>
                <item id="d1"><dc:title>book.pdf</dc:title><upnp:class>object.item.textItem</upnp:class>
                <res protocolInfo="http-get:*:application/pdf:*">http://s/media/book.pdf</res></item>""",
            ),
        )
        assertEquals(MediaType.IMAGE, entries[0].type)
        assertEquals(MediaType.DOCUMENT, entries[1].type)
        assertEquals("http://s/media/book.pdf", entries[1].url)
        assertEquals(MediaType.IMAGE, Upnp.typeOf("object.item.imageItem"))
        assertEquals(MediaType.OTHER, Upnp.typeOf("object.item.playlistItem"))
    }

    @Test
    fun `entries without an id are skipped, and a missing title falls back to the id`() {
        val entries = Upnp.didl(didl("""<container><dc:title>Nameless</dc:title></container><container id="7"/>"""))
        assertEquals(listOf(DidlEntry("7", "7", MediaType.FOLDER, null, null)), entries)
    }

    @Test
    fun `an empty result is an empty list`() {
        assertEquals(emptyList<DidlEntry>(), Upnp.didl(""))
        assertEquals(emptyList<DidlEntry>(), Upnp.didl(didl("")))
    }

    @Test
    fun `malformed XML is an IO error`() {
        assertThrows(IOException::class.java) { Upnp.didl("<DIDL-Lite><item id=\"1\">") }
        assertThrows(IOException::class.java) { Upnp.didl("not xml at all") }
        assertThrows(IOException::class.java) { Upnp.browseResponse("<s:Envelope>") }
    }

    @Test
    fun `browse responses unwrap the escaped DIDL and the paging counts`() {
        val page = Upnp.browseResponse(browseResponse(didl("""<container id="1"><dc:title>Films</dc:title></container>"""), 1, 5))
        assertEquals(BrowsePage(listOf(DidlEntry("1", "Films", MediaType.FOLDER, null, null)), 1, 5), page)
    }

    @Test
    fun `SOAP faults are IO errors carrying the UPnP description`() {
        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode>
            <errorDescription>No such object</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        val error = assertThrows(IOException::class.java) { Upnp.browseResponse(fault) }
        assertEquals("Browse failed: No such object", error.message)
    }
}

class UpnpDiscoveryTest {
    @Test
    fun `reads LOCATION from an SSDP reply, ignoring case`() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nlocation: http://10.0.0.2:8200/rootDesc.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
        assertEquals("http://10.0.0.2:8200/rootDesc.xml", Upnp.ssdpLocation(reply))
        assertNull(Upnp.ssdpLocation("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
    }

    private val description = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major></specVersion>
        <device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
        <friendlyName>Agumon: minidlna</friendlyName><UDN>uuid:4d696e69-444c-164e-9d41-0050b6000001</UDN>
        <serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ctl/ConnectionMgr</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/ctl/ContentDir</controlURL></service>
        </serviceList></device></root>"""

    @Test
    fun `a device description gives the name, UDN and absolute control URL`() {
        assertEquals(
            DlnaServer("uuid:4d696e69-444c-164e-9d41-0050b6000001", "Agumon: minidlna", "http://10.0.0.2:8200/ctl/ContentDir"),
            Upnp.device("http://10.0.0.2:8200/rootDesc.xml", description),
        )
    }

    @Test
    fun `URLBase overrides the description location, and newer ContentDirectory versions count`() {
        val withBase = description
            .replace("<specVersion>", "<URLBase>http://10.0.0.9:9000/dlna/</URLBase><specVersion>")
            .replace("ContentDirectory:1", "ContentDirectory:2")
            .replace("/ctl/ContentDir", "ctl/ContentDir")
        assertEquals("http://10.0.0.9:9000/dlna/ctl/ContentDir", Upnp.device("http://10.0.0.2:8200/rootDesc.xml", withBase).controlUrl)
    }

    @Test
    fun `a device without ContentDirectory is an IO error`() {
        val renderer = description.replace("ContentDirectory", "RenderingControl")
        assertThrows(IOException::class.java) { Upnp.device("http://10.0.0.2/d.xml", renderer) }
    }
}

class DlnaMediaSourceTest {
    private val server = DlnaServer("uuid:1", "NAS", "http://nas/ctl")
    private val tree = mapOf(
        "0" to listOf(DidlEntry("v", "Video", MediaType.FOLDER, null, null)),
        "v" to listOf(
            DidlEntry("v1", "Film", MediaType.VIDEO, "http://nas/1.mkv", 10),
            DidlEntry("v2", "Unplayable", MediaType.VIDEO, null, null),
        ),
    )
    private val browsed = mutableListOf<String>()
    private val source = DlnaMediaSource(server) { _, id -> browsed += id; tree.getValue(id) }

    @Test
    fun `the root is object 0 and folders use object ids`() {
        assertEquals(listOf(MediaItem.folder("Video", "v")), source.list(""))
        assertEquals(listOf("0"), browsed)
    }

    @Test
    fun `items without a URL are left out, and listed items open as their URL`() {
        val items = source.list("v")
        assertEquals(listOf(MediaItem("Film", "v1", MediaType.VIDEO, 10)), items)
        assertEquals(MediaStream.Location("http://nas/1.mkv"), source.open(items[0]))
        assertThrows(IllegalArgumentException::class.java) { source.open(MediaItem.folder("Video", "v")) }
    }

    @Test
    fun `id and name come from the server`() {
        assertEquals("dlna:uuid:1", source.id)
        assertEquals("NAS", source.displayName)
    }

    @Test
    fun `DLNA targets resume by URL`() {
        val target = PlaybackTarget.dlna("uuid:1", "http://nas/1.mkv", "Film")
        assertEquals(PlaybackTarget("dlna:uuid:1", "http://nas/1.mkv", "Film", "http://nas/1.mkv"), target)
        val entry = ResumeEntry("dlna:uuid:1", "http://nas/1.mkv", "Film", 60_000, 600_000, 1)
        assertEquals(target, PlaybackTarget.forEntry(entry))
        assertTrue(!target.isLocalFile)
    }
}
