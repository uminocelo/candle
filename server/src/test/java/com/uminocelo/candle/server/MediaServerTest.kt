package com.uminocelo.candle.server

import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import com.uminocelo.candle.upnp.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File

class MediaServerTest {
    private val root = storage(
        "Movies/Holiday & <Friends>.mkv", "Movies/b.mp4", "Movies/c.mp4", "Movies/Series/e1.mkv",
        "Pictures/beach.jpg", "Documents/book.pdf",
    )
    private var sharing = true
    private val thumb = File(root, "thumb.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    private val server = MediaServer(
        "uuid-1", { "Living Room Phone & co" },
        MediaIndex({ listOf("Movies", "Pictures", "Documents").map { SharedFolder(it, File(root, it), true) } }),
        { item -> if (item.type.kind == MediaKind.IMAGE) thumb else null },
        { sharing },
    )

    private fun request(method: String, path: String, body: String = "", headers: Map<String, String> = emptyMap()) =
        server.handle(HttpRequest(method, path, emptyMap(), mapOf("host" to "192.0.2.169:8766") + headers, body.toByteArray()))

    private fun soap(service: String, action: String, args: String): HttpResponse {
        val type = "urn:schemas-upnp-org:service:$service:1"
        val envelope = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:$action xmlns:u="$type">$args</u:$action></s:Body></s:Envelope>"""
        return request("POST", "/dlna/control/$service", envelope, mapOf("soapaction" to "\"$type#$action\""))!!
    }

    private fun browse(id: String, flag: String = "BrowseDirectChildren", start: Int = 0, count: Int = 0) = soap(
        "ContentDirectory", "Browse",
        "<ObjectID>${id.replace("&", "&amp;").replace("<", "&lt;")}</ObjectID><BrowseFlag>$flag</BrowseFlag><Filter>*</Filter>" +
            "<StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>",
    )

    private fun HttpResponse.doc(): Document = Xml.parse(body.toString(Charsets.UTF_8))

    private fun Document.text(name: String): String? = getElementsByTagNameNS("*", name).item(0)?.textContent

    /** The DIDL-Lite of a Browse response, parsed. */
    private fun HttpResponse.didl(): Document = Xml.parse(doc().text("Result")!!)

    private fun Document.entries(): List<Element> =
        documentElement.childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>() }

    private fun Element.child(name: String): String? = getElementsByTagNameNS("*", name).item(0)?.textContent

    @Test
    fun `the root lists the shared folders as storage folders`() {
        val response = browse("0")
        assertEquals(200, response.status)
        val entries = response.didl().entries()
        assertEquals(listOf("Documents", "Movies", "Pictures"), entries.mapNotNull { it.child("title") }.sorted())
        assertTrue(entries.all { it.localName == "container" && it.getAttribute("parentID") == "0" })
        assertEquals("object.container.storageFolder", entries.first().child("class"))
        assertEquals("3", response.doc().text("TotalMatches"))
    }

    @Test
    fun `a nested folder lists folders, then items with stream URL, size, type and thumbnail`() {
        val entries = browse("Movies").didl().entries()
        assertEquals(listOf("Series", "b", "c", "Holiday & <Friends>").sorted(), entries.mapNotNull { it.child("title") }.sorted())
        val holiday = entries.single { it.getAttribute("id") == "Movies/Holiday & <Friends>.mkv" }
        assertEquals("Movies", holiday.getAttribute("parentID"))
        assertEquals("object.item.videoItem", holiday.child("class"))
        val res = holiday.getElementsByTagNameNS("*", "res").item(0) as Element
        assertEquals("http://192.0.2.169:8766/dlna/media/Movies/Holiday%20%26%20%3CFriends%3E.mkv", res.textContent)
        assertTrue(res.getAttribute("protocolInfo"), res.getAttribute("protocolInfo").startsWith("http-get:*:video/x-matroska:DLNA.ORG_OP=01"))
        assertEquals("Movies/Holiday & <Friends>.mkv".length.toString(), res.getAttribute("size")) // storage() writes one byte per path character
        assertEquals("http://192.0.2.169:8766/dlna/thumb/Movies/Holiday%20%26%20%3CFriends%3E.mkv", holiday.child("albumArtURI"))
        assertEquals("container", entries.first().localName)
    }

    @Test
    fun `photos and documents get their classes`() {
        assertEquals("object.item.imageItem.photo", browse("Pictures").didl().entries().single().child("class"))
        val book = browse("Documents").didl().entries().single()
        assertEquals("object.item.textItem", book.child("class"))
        assertEquals("book.pdf", book.child("title"))
        assertNull("no thumbnail for documents", book.child("albumArtURI"))
    }

    @Test
    fun `pages with StartingIndex and RequestedCount`() {
        val first = browse("Movies", start = 0, count = 2)
        assertEquals(listOf("Series", "b"), first.didl().entries().map { it.child("title") })
        assertEquals("2", first.doc().text("NumberReturned"))
        assertEquals("4", first.doc().text("TotalMatches"))
        val rest = browse("Movies", start = 2, count = 10)
        assertEquals(listOf("c", "Holiday & <Friends>"), rest.didl().entries().map { it.child("title") })
        val past = browse("Movies", start = 9, count = 2)
        assertEquals("0", past.doc().text("NumberReturned"))
        assertEquals("4", past.doc().text("TotalMatches"))
    }

    @Test
    fun `BrowseMetadata describes the entry itself`() {
        val root = browse("0", flag = "BrowseMetadata").didl().entries().single()
        assertEquals("-1", root.getAttribute("parentID"))
        assertEquals("3", root.getAttribute("childCount"))
        val film = browse("Movies/b.mp4", flag = "BrowseMetadata").didl().entries().single()
        assertEquals("item", film.localName)
    }

    @Test
    fun `unknown objects are 701 and bad arguments 402`() {
        fun code(response: HttpResponse) = response.doc().text("errorCode")
        assertEquals("701", code(browse("Movies/nope.mp4")))
        assertEquals("701", code(browse("../etc")))
        assertEquals("402", code(browse("0", flag = "Sideways")))
        assertEquals("402", code(soap("ContentDirectory", "Browse", "<ObjectID>0</ObjectID>")))
        assertEquals("401", code(soap("ContentDirectory", "GetProtocolInfo", "")))
        assertEquals(500, browse("nope").status)
    }

    @Test
    fun `ConnectionManager and the other ContentDirectory actions answer`() {
        assertTrue(soap("ConnectionManager", "GetProtocolInfo", "").doc().text("Source")!!.contains("http-get:*:video/mp4:*"))
        assertEquals("0", soap("ConnectionManager", "GetCurrentConnectionIDs", "").doc().text("ConnectionIDs"))
        assertTrue(soap("ContentDirectory", "GetSystemUpdateID", "").doc().text("Id")!!.toInt() >= 1)
    }

    @Test
    fun `the description escapes the name and lists both services`() {
        val doc = request("GET", "/dlna/device.xml")!!.doc()
        assertEquals("Living Room Phone & co", doc.text("friendlyName"))
        assertEquals("uuid:uuid-1", doc.text("UDN"))
        assertEquals(200, request("GET", "/dlna/ContentDirectory.xml")!!.status)
        assertEquals(200, request("GET", "/dlna/ConnectionManager.xml")!!.status)
        assertEquals(404, request("GET", "/dlna/Nope.xml")!!.status)
    }

    @Test
    fun `files stream with DLNA headers and byte ranges`() {
        val response = request("GET", "/dlna/media/Movies/b.mp4", headers = mapOf("range" to "bytes=2-"))!!
        assertEquals(206, response.status)
        assertEquals("video/mp4", response.contentType)
        assertEquals("Streaming", response.headers["transferMode.dlna.org"])
        assertTrue(response.headers["contentFeatures.dlna.org"]!!.startsWith("DLNA.ORG_OP=01"))
        val out = ByteArrayOutputStream().also { response.writeTo(it) }.toString("UTF-8")
        assertTrue(out, out.endsWith("\r\n\r\n" + "x".repeat("Movies/b.mp4".length - 2)))
        assertEquals("Interactive", request("GET", "/dlna/media/Pictures/beach.jpg")!!.headers["transferMode.dlna.org"])
    }

    @Test
    fun `only indexed files are served, and thumbnails only when there is one`() {
        assertEquals(404, request("GET", "/dlna/media/Movies/Series")!!.status)
        assertEquals(404, request("GET", "/dlna/media/../thumb.jpg")!!.status)
        assertEquals(404, request("GET", "/dlna/media/thumb.jpg")!!.status)
        assertEquals("image/jpeg", request("GET", "/dlna/thumb/Pictures/beach.jpg")!!.contentType)
        assertEquals(404, request("GET", "/dlna/thumb/Movies/b.mp4")!!.status)
    }

    @Test
    fun `sharing off answers 404 everywhere under dlna, and leaves other paths alone`() {
        sharing = false
        assertEquals(404, request("GET", "/dlna/device.xml")!!.status)
        assertEquals(404, browse("0").status)
        assertEquals(404, request("GET", "/dlna/media/Movies/b.mp4")!!.status)
        assertNull(request("GET", "/ping"))
        assertFalse(sharing)
    }
}
