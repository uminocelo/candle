package com.uminocelo.candle.server

import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ServerApiTest {
    private val root = storage(
        "Movies/Holiday.mkv", "Movies/Series/S1/Holiday special.mp4", "Movies/notes.apk",
        "Pictures/beach.jpg", "Documents/book.pdf", "Private/secret.mp4",
    )
    private val store = MapStore()
    private val settings = ServerSettings(store, root, "Living Room Phone").apply { unshare("DCIM"); unshare("Music"); unshare("Download") }
    private val auth = WebAuth(store)
    private val index = MediaIndex(settings::sharedFolders)
    private val thumb = File(root, "t.jpg").apply { writeBytes(byteArrayOf(9)) }
    private val thumbnails = Thumbnails { item -> if (item.type.kind == MediaKind.IMAGE) thumb else null }
    private val api = ServerApi(
        settings, "0.1.0", MediaServer("u", { settings.name }, index, thumbnails, { true }), index, thumbnails, auth,
        Uploads(File(root, Uploads.FOLDER), freeBytes = { Long.MAX_VALUE }),
    ) { name -> if (name == "index.html" || name == "manifest.json") "<$name>".toByteArray() else null }

    private var cookie: String? = null

    private fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = "", upload: ByteArray? = null): HttpResponse {
        val headers = buildMap {
            put("host", "phone:8766")
            cookie?.let { put("cookie", "${WebAuth.COOKIE}=$it") }
            if (body.isNotEmpty()) put("content-type", "application/x-www-form-urlencoded")
        }
        val request = if (upload != null) {
            HttpRequest(method, path, query, headers, ByteArray(0), "10.0.0.2", ByteArrayInputStream(upload), upload.size.toLong())
        } else {
            HttpRequest(method, path, query, headers, body.toByteArray(), "10.0.0.2")
        }
        return api.handle(request)
    }

    private fun get(path: String, vararg query: Pair<String, String>) = call("GET", path, query.toMap())

    private fun HttpResponse.text() = body.toString(Charsets.UTF_8)

    private fun logIn() {
        val response = call("POST", "/api/login", body = "password=open+sesame")
        assertEquals(response.text(), 200, response.status)
        cookie = response.headers.getValue("Set-Cookie").substringAfter("=").substringBefore(";")
    }

    @Test
    fun `lists the shared folders, then a folder's children with kinds`() {
        val rootList = get("/api/list").text()
        assertTrue(rootList, """"total":3""" in rootList)
        assertFalse("unshared folders are not listed", "Private" in rootList)
        val movies = get("/api/list", "path" to "Movies").text()
        assertTrue(movies, """{"name":"Series","path":"Movies/Series","kind":"folder","count":1}""" in movies)
        assertTrue(movies, """"name":"Holiday.mkv","path":"Movies/Holiday.mkv","kind":"video","mime":"video/x-matroska"""" in movies)
        assertTrue(movies, """"parent":""""" in movies)
        assertFalse("unknown types are left out", "notes.apk" in movies)
    }

    @Test
    fun `large folders come in pages`() {
        val page = get("/api/list", "path" to "Movies", "offset" to "1", "limit" to "1").text()
        assertTrue(page, """"total":2,"offset":1""" in page)
        assertTrue(page, "Holiday.mkv" in page && "Series" !in page)
    }

    @Test
    fun `search finds files and folders by name anywhere, ignoring case`() {
        val found = get("/api/search", "q" to "holiday").text()
        assertTrue(found, "Movies/Holiday.mkv" in found && "Movies/Series/S1/Holiday special.mp4" in found)
        assertTrue(found, """"total":2""" in found)
        assertEquals(400, get("/api/search", "q" to "h").status)
        assertTrue("unshared folders are not searched", get("/api/search", "q" to "secret").text().contains(""""total":0"""))
    }

    @Test
    fun `paths outside the shared folders are 404`() {
        assertEquals(404, get("/api/list", "path" to "Private").status)
        assertEquals(404, get("/api/list", "path" to "../Private").status)
        assertEquals(404, get("/files/Private/secret.mp4").status)
        assertEquals(404, get("/files/Movies/../Private/secret.mp4").status)
        assertEquals(404, get("/files/Movies/notes.apk").status)
        assertEquals(404, get("/files/Movies").status)
        assertEquals(404, get("/thumbs/Movies/Holiday.mkv").status)
        assertEquals(404, get("/../etc/passwd").status)
    }

    @Test
    fun `files open inline with ranges, or download with their name`() {
        val inline = call("GET", "/files/Movies/Holiday.mkv")
        assertEquals("video/x-matroska", inline.contentType)
        assertEquals("inline; filename*=UTF-8''Holiday.mkv", inline.headers["Content-Disposition"])
        assertEquals("bytes", inline.headers["Accept-Ranges"])
        val download = get("/files/Movies/Series/S1/Holiday special.mp4", "download" to "1")
        assertEquals("attachment; filename*=UTF-8''Holiday%20special.mp4", download.headers["Content-Disposition"])
        assertEquals("image/jpeg", get("/thumbs/Pictures/beach.jpg").contentType)
    }

    @Test
    fun `the page and its files are always served`() {
        auth.setPassword("open sesame")
        assertEquals("<index.html>", get("/").text())
        assertEquals("application/manifest+json", get("/manifest.json").contentType)
        assertEquals(404, get("/nope.js").status)
    }

    /** What each endpoint answers in each mode, logged in or not. */
    private fun statuses(): List<Int> = listOf(
        get("/ping").status,
        get("/").status,
        get("/api/session").status,
        get("/api/status").status,
        get("/api/list").status,
        get("/api/search", "q" to "holiday").status,
        get("/files/Movies/Holiday.mkv").status,
        get("/thumbs/Pictures/beach.jpg").status,
        call("POST", "/api/upload", mapOf("name" to "a.mp4"), upload = byteArrayOf(1)).status,
    )

    @Test
    fun `no password yet - browsing is open and uploads are off`() {
        assertEquals(listOf(200, 200, 200, 200, 200, 200, 200, 200, 401), statuses())
        assertEquals(409, call("POST", "/api/login", body = "password=x").status)
    }

    @Test
    fun `public - browsing is open, uploading needs a login`() {
        auth.setPassword("open sesame")
        auth.mode = WebMode.PUBLIC
        assertEquals(listOf(200, 200, 200, 200, 200, 200, 200, 200, 401), statuses())
        logIn()
        assertEquals(listOf(200, 200, 200, 200, 200, 200, 200, 200, 200), statuses())
    }

    @Test
    fun `private - only the page, ping and the session answer until login`() {
        auth.setPassword("open sesame")
        auth.mode = WebMode.PRIVATE
        assertEquals(listOf(200, 200, 200, 401, 401, 401, 401, 401, 401), statuses())
        val session = get("/api/session").text()
        assertTrue(session, """"mode":"private","passwordSet":true,"loggedIn":false,"canBrowse":false""" in session)
        logIn()
        assertEquals(listOf(200, 200, 200, 200, 200, 200, 200, 200, 200), statuses())
    }

    @Test
    fun `login sets a 30-day cookie, logout clears it, a new password signs everyone out`() {
        auth.setPassword("open sesame")
        val response = call("POST", "/api/login", body = "password=open+sesame")
        val setCookie = response.headers.getValue("Set-Cookie")
        assertTrue(setCookie, "Max-Age=2592000" in setCookie && "HttpOnly" in setCookie)
        logIn()
        assertTrue(get("/api/session").text().contains(""""loggedIn":true"""))
        auth.setPassword("new password")
        assertTrue(get("/api/session").text().contains(""""loggedIn":false"""))
        assertTrue(call("POST", "/api/logout").headers.getValue("Set-Cookie").startsWith("${WebAuth.COOKIE}=;"))
    }

    @Test
    fun `wrong passwords count down, then lock out`() {
        auth.setPassword("open sesame")
        val wrong = call("POST", "/api/login", body = "password=nope")
        assertEquals(401, wrong.status)
        assertTrue(wrong.text(), """"triesLeft":4""" in wrong.text())
        repeat(3) { call("POST", "/api/login", body = "password=nope") }
        assertEquals(429, call("POST", "/api/login", body = "password=nope").status)
        assertEquals("locked even with the right one", 429, call("POST", "/api/login", body = "password=open+sesame").status)
    }

    @Test
    fun `an upload goes into the open folder and is listed at once`() {
        auth.setPassword("open sesame")
        logIn()
        get("/api/list", "path" to "Movies/Series/S1") // builds the index before the upload
        val response = call("POST", "/api/upload", mapOf("name" to "e2.mp4", "folder" to "Movies/Series/S1"), upload = ByteArray(4))
        assertEquals(response.text(), 200, response.status)
        assertTrue(response.text(), """"folder":"Movies/Series/S1","path":"Movies/Series/S1/e2.mp4"""" in response.text())
        assertEquals(4L, File(root, "Movies/Series/S1/e2.mp4").length())
        assertTrue(get("/api/list", "path" to "Movies/Series/S1").text().contains("e2.mp4"))
        assertFalse("Candle Uploads is only for uploads with no folder", Uploads.FOLDER in settings.sharedNames)
    }

    @Test
    fun `uploads only go into shared folders`() {
        auth.setPassword("open sesame")
        logIn()
        for (folder in listOf("Private", "Movies/../Private", "Movies/Holiday.mkv", "Nope", "0")) {
            assertEquals(folder, 404, call("POST", "/api/upload", mapOf("name" to "x.mp4", "folder" to folder), upload = ByteArray(1)).status)
        }
        assertFalse(File(root, "Private/x.mp4").exists())
    }

    @Test
    fun `an upload with no folder lands in Candle Uploads, which becomes shared and listed at once`() {
        auth.setPassword("open sesame")
        logIn()
        get("/api/list") // builds the index before the upload
        val response = call("POST", "/api/upload", mapOf("name" to "../../clip.mp4"), upload = ByteArray(10) { it.toByte() })
        assertEquals(response.text(), 200, response.status)
        assertTrue(response.text(), """"path":"Candle Uploads/clip.mp4"""" in response.text())
        assertTrue(Uploads.FOLDER in settings.sharedNames)
        assertTrue(get("/api/list", "path" to Uploads.FOLDER).text().contains("clip.mp4"))
        assertNotNull(File(root, "Candle Uploads/clip.mp4").takeIf { it.length() == 10L })
    }
}
