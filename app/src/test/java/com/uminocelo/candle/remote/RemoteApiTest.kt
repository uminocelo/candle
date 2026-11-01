package com.uminocelo.candle.remote

import android.view.KeyEvent
import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import com.uminocelo.candle.media.FakeMediaSource
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaType
import com.uminocelo.candle.player.PlaybackKind
import com.uminocelo.candle.player.PlaybackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

private class MemoryTokenStore : TokenStore {
    var hashes = emptySet<String>()

    override fun load() = hashes

    override fun save(hashes: Set<String>) {
        this.hashes = hashes
    }
}

/** Records commands; [candleInFront] decides whether key presses reach Candle. */
private class FakeDevice(var candleInFront: Boolean = true) : RemoteDevice {
    val pins = mutableListOf<String>()
    val keys = mutableListOf<Int>()
    val mediaKeys = mutableListOf<Int>()
    val volumes = mutableListOf<VolumeCommand>()
    var homes = 0

    override fun showPin(pin: String) {
        pins += pin
    }

    override fun pressKey(keyCode: Int): Boolean {
        if (candleInFront) keys += keyCode
        return candleInFront
    }

    override fun mediaKey(keyCode: Int) {
        mediaKeys += keyCode
    }

    override fun volume(command: VolumeCommand) {
        volumes += command
    }

    override fun goHome() {
        homes++
    }
}

/** Sources by id; [played] records what the phone started; sources in [unplayable] refuse. */
private class FakeLibrary(vararg sources: MediaSource) : RemoteLibrary {
    private val byId = sources.associateBy { it.id }
    val played = mutableListOf<Pair<String, MediaItem>>()
    var unplayable = emptySet<String>()

    override fun sources() = byId.values.toList()

    override fun source(id: String) = byId[id]

    override fun play(source: MediaSource, item: MediaItem): Boolean {
        if (source.id in unplayable) return false
        played += source.id to item
        return true
    }
}

class PairingTest {
    private var clock = 0L
    private val store = MemoryTokenStore()
    private val pairing = Pairing(store, now = { clock }, random = Random(7))

    @Test
    fun `the right PIN pairs once and the token is remembered as a hash`() {
        val pin = pairing.start()
        assertTrue(pin.matches(Regex("\\d{4}")))
        val result = pairing.pair(pin) as PairResult.Paired
        assertTrue(pairing.isPaired(result.token))
        assertFalse(result.token in store.hashes)
        assertEquals(PairResult.NoPin, pairing.pair(pin))
    }

    @Test
    fun `a wrong PIN is refused, and too many misses cancel the PIN`() {
        val pin = pairing.start()
        val wrong = if (pin == "0000") "0001" else "0000"
        repeat(Pairing.MAX_ATTEMPTS - 1) { assertEquals(PairResult.WrongPin, pairing.pair(wrong)) }
        assertEquals(PairResult.WrongPin, pairing.pair(wrong))
        assertEquals(PairResult.NoPin, pairing.pair(pin))
    }

    @Test
    fun `an expired PIN no longer pairs`() {
        val pin = pairing.start()
        clock += Pairing.PIN_LIFETIME_MS + 1
        assertEquals(PairResult.NoPin, pairing.pair(pin))
        assertEquals(PairResult.NoPin, Pairing(store).pair("1234"))
    }

    @Test
    fun `forgetting phones invalidates every token and the PIN on screen`() {
        val first = (pairing.pair(pairing.start()) as PairResult.Paired).token
        val second = (pairing.pair(pairing.start()) as PairResult.Paired).token
        assertNotEquals(first, second)
        val pin = pairing.start()
        pairing.forgetAll()
        assertFalse(pairing.isPaired(first))
        assertFalse(pairing.isPaired(second))
        assertEquals(PairResult.NoPin, pairing.pair(pin))
        assertFalse(pairing.isPaired(null))
    }
}

class RemoteApiTest {
    private val device = FakeDevice()
    private val pairing = Pairing(MemoryTokenStore(), random = Random(1))
    private val usb = FakeMediaSource(
        mapOf(
            "" to listOf(
                MediaItem.file("b film.mkv", "/usb/b film.mkv", 2048),
                MediaItem.file("notes.txt", "/usb/notes.txt", 10),
                MediaItem.folder("Music", "/usb/Music"),
                MediaItem.file("a \"quoted\".mp4", "/usb/a.mp4"),
            ),
            "/usb/Music" to listOf(MediaItem.file("song.mp3", "/usb/Music/song.mp3", 5)),
        ),
        id = "usb:3A39-15F1",
        displayName = "USB stick",
    )
    private val library = FakeLibrary(usb, FakeMediaSource(emptyMap(), id = "smb:nas", displayName = "Media on nas"))
    private var playing: PlaybackSnapshot? = null
    private val api = RemoteApi(pairing, device, library, { playing }, { 5_000L }) { name ->
        if (name == "index.html") "page".toByteArray() else null
    }

    private fun call(
        method: String,
        path: String,
        token: String? = null,
        body: String = "",
        cookie: String? = null,
    ): HttpResponse {
        val headers = buildMap {
            token?.let { put("authorization", "Bearer $it") }
            cookie?.let { put("cookie", it) }
        }
        return api.handle(HttpRequest(method, path, emptyMap(), headers, body.toByteArray()))
    }

    private fun HttpResponse.text() = body.toString(Charsets.UTF_8)

    private fun get(path: String, token: String, query: Map<String, String> = emptyMap()) =
        api.handle(HttpRequest("GET", path, query, mapOf("authorization" to "Bearer $token"), ByteArray(0)))

    private fun pairPhone(): String {
        assertEquals(202, call("POST", "/api/pair/start").status)
        val response = call("POST", "/api/pair", body = "pin=${device.pins.last()}")
        assertEquals(200, response.status)
        return Regex("\"token\":\"([0-9a-f]+)\"").find(response.text())!!.groupValues[1]
    }

    @Test
    fun `pairing shows the PIN on the projector and sets a cookie`() {
        call("POST", "/api/pair/start")
        assertEquals(1, device.pins.size)
        val response = call("POST", "/api/pair", body = "pin=${device.pins.single()}")
        assertTrue(response.headers.getValue("Set-Cookie").startsWith("${RemoteApi.COOKIE}="))
        assertFalse(device.pins.single() in response.text())
    }

    @Test
    fun `a wrong PIN is 403 and no PIN is 410`() {
        call("POST", "/api/pair/start")
        val wrong = if (device.pins.single() == "0000") "0001" else "0000"
        assertEquals(403, call("POST", "/api/pair", body = "pin=$wrong").status)
        assertEquals(410, Pairing(MemoryTokenStore()).let { RemoteApi(it, device, library, { null }, { 0L }) }
            .handle(HttpRequest("POST", "/api/pair", emptyMap(), emptyMap(), "pin=1234".toByteArray())).status)
    }

    @Test
    fun `commands without a valid token are 401 and do nothing`() {
        for (path in listOf("/api/key/ok", "/api/volume/up", "/api/home")) {
            assertEquals(path, 401, call("POST", path).status)
            assertEquals(path, 401, call("POST", path, token = "not-a-token").status)
        }
        assertTrue(device.keys.isEmpty() && device.volumes.isEmpty() && device.homes == 0)
    }

    @Test
    fun `a forgotten token is 401`() {
        val token = pairPhone()
        pairing.forgetAll()
        assertEquals(401, call("POST", "/api/key/ok", token).status)
    }

    @Test
    fun `keys map to the remote's key codes, by header or cookie`() {
        val token = pairPhone()
        val expected = mapOf(
            "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ok" to KeyEvent.KEYCODE_DPAD_CENTER, "back" to KeyEvent.KEYCODE_BACK,
            "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "seek_back" to KeyEvent.KEYCODE_MEDIA_REWIND,
            "seek_forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        for (name in expected.keys) assertEquals(name, 200, call("POST", "/api/key/$name", token).status)
        assertEquals(expected.values.toList(), device.keys)
        assertEquals(200, call("POST", "/api/key/OK", cookie = "x=1; ${RemoteApi.COOKIE}=$token").status)
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, device.keys.last())
    }

    @Test
    fun `with another app in front, media keys go to the media session and navigation is 409`() {
        val token = pairPhone()
        device.candleInFront = false
        assertEquals(200, call("POST", "/api/key/play_pause", token).status)
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), device.mediaKeys)
        val response = call("POST", "/api/key/ok", token)
        assertEquals(409, response.status)
        assertTrue(response.text().contains("not in front"))
    }

    @Test
    fun `volume and home commands`() {
        val token = pairPhone()
        for (name in listOf("up", "down", "mute")) assertEquals(200, call("POST", "/api/volume/$name", token).status)
        assertEquals(listOf(VolumeCommand.UP, VolumeCommand.DOWN, VolumeCommand.MUTE), device.volumes)
        assertEquals(200, call("POST", "/api/home", token).status)
        assertEquals(1, device.homes)
    }

    @Test
    fun `unknown commands are 404`() {
        val token = pairPhone()
        assertEquals(404, call("POST", "/api/key/launch_missiles", token).status)
        assertEquals(404, call("POST", "/api/volume/max", token).status)
        assertEquals(404, call("POST", "/api/nothing", token).status)
        assertTrue(device.keys.isEmpty() && device.volumes.isEmpty())
    }

    @Test
    fun `library and now-playing need a token`() {
        for (path in listOf("/api/now-playing", "/api/sources", "/api/browse")) assertEquals(path, 401, call("GET", path).status)
        assertEquals(401, call("POST", "/api/play", body = "source=usb:3A39-15F1&path=/usb/a.mp4&name=a&type=video").status)
        assertTrue(library.played.isEmpty())
    }

    @Test
    fun `now playing reports the player, with the position projected to now`() {
        val token = pairPhone()
        playing = PlaybackSnapshot(PlaybackKind.VIDEO, "Film", true, positionMs = 1_000, durationMs = 60_000, reportedAt = 4_000)
        assertEquals(
            """{"state":"playing","kind":"video","title":"Film","positionMs":2000,"durationMs":60000}""",
            get("/api/now-playing", token).text(),
        )
    }

    @Test
    fun `sources list id, name and kind`() {
        val token = pairPhone()
        assertEquals(
            """{"ok":true,"sources":[{"id":"usb:3A39-15F1","name":"USB stick","kind":"usb"},""" +
                """{"id":"smb:nas","name":"Media on nas","kind":"smb"}]}""",
            get("/api/sources", token).text(),
        )
    }

    @Test
    fun `browse lists folders first, then videos and music, without other files`() {
        val token = pairPhone()
        val response = get("/api/browse", token, mapOf("source" to "usb:3A39-15F1", "path" to ""))
        assertEquals(200, response.status)
        assertEquals(
            """{"ok":true,"items":[{"name":"Music","path":"/usb/Music","type":"folder","size":null},""" +
                """{"name":"a \"quoted\".mp4","path":"/usb/a.mp4","type":"video","size":null},""" +
                """{"name":"b film.mkv","path":"/usb/b film.mkv","type":"video","size":2048}]}""",
            response.text(),
        )
        val music = get("/api/browse", token, mapOf("source" to "usb:3A39-15F1", "path" to "/usb/Music")).text()
        assertTrue(music, music.contains(""""type":"audio""""))
    }

    @Test
    fun `browsing an unknown source is 404 and an unreadable folder is an error, not a crash`() {
        val token = pairPhone()
        val unknown = get("/api/browse", token, mapOf("source" to "usb:gone"))
        assertEquals(404, unknown.status)
        assertTrue(unknown.text().contains("unknown source"))
        val unreadable = get("/api/browse", token, mapOf("source" to "smb:nas", "path" to "Locked"))
        assertEquals(502, unreadable.status)
        assertTrue(unreadable.text(), unreadable.text().startsWith("""{"error":"cannot read this folder: """))
    }

    @Test
    fun `play starts the item on the projector`() {
        val token = pairPhone()
        val response = call("POST", "/api/play", token, body = "source=usb%3A3A39-15F1&path=%2Fusb%2Fb+film.mkv&name=b+film.mkv&type=video")
        assertEquals(200, response.status)
        assertEquals(listOf("usb:3A39-15F1" to MediaItem("b film.mkv", "/usb/b film.mkv", MediaType.VIDEO)), library.played)
    }

    @Test
    fun `play refuses folders, unknown sources and sources Candle cannot play from`() {
        val token = pairPhone()
        assertEquals(400, call("POST", "/api/play", token, body = "source=usb:3A39-15F1&path=/usb/Music&name=Music&type=folder").status)
        assertEquals(400, call("POST", "/api/play", token, body = "source=usb:3A39-15F1&path=/usb/x&name=x").status)
        assertEquals(404, call("POST", "/api/play", token, body = "source=usb:gone&path=/a&name=a&type=video").status)
        library.unplayable = setOf("smb:nas")
        assertEquals(409, call("POST", "/api/play", token, body = "source=smb:nas&path=a.mkv&name=a&type=video").status)
        assertTrue(library.played.isEmpty())
    }

    @Test
    fun `ping and the page need no token, and paths cannot leave the page folder`() {
        assertEquals(200, call("GET", "/ping").status)
        val page = call("GET", "/")
        assertEquals("page", page.text())
        assertEquals("text/html; charset=utf-8", page.contentType)
        assertEquals(404, call("GET", "/../secret").status)
        assertEquals(404, call("GET", "/missing.js").status)
    }
}
