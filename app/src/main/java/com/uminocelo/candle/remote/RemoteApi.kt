package com.uminocelo.candle.remote

import android.view.KeyEvent
import com.uminocelo.candle.browse.PLAYABLE
import com.uminocelo.candle.browse.browsable
import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import com.uminocelo.candle.http.Json
import com.uminocelo.candle.http.Router
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaType
import com.uminocelo.candle.player.PlaybackSnapshot

/** What the remote can do on the projector; [AndroidRemoteDevice] on the device, a fake in tests. */
interface RemoteDevice {
    fun showPin(pin: String)

    /** Presses a key in Candle's focused window; false when another app is in front. */
    fun pressKey(keyCode: Int): Boolean

    /** Sends a media key to the active media session (Candle's music, or another app's player). */
    fun mediaKey(keyCode: Int)

    fun volume(command: VolumeCommand)

    fun goHome()
}

/** The sources the phone can browse; [AndroidRemoteLibrary] on the device, fakes in tests. Calls may block on I/O. */
interface RemoteLibrary {
    fun sources(): List<MediaSource>

    fun source(id: String): MediaSource?

    /** Starts [item] on the projector; false when Candle cannot play from [source]. */
    fun play(source: MediaSource, item: MediaItem): Boolean
}

/** `/api/key/{name}` commands. Media keys still work when another app is in front. */
enum class RemoteKey(val keyCode: Int, val media: Boolean = false) {
    UP(KeyEvent.KEYCODE_DPAD_UP),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
    OK(KeyEvent.KEYCODE_DPAD_CENTER),
    BACK(KeyEvent.KEYCODE_BACK),
    PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, media = true),
    SEEK_BACK(KeyEvent.KEYCODE_MEDIA_REWIND, media = true),
    SEEK_FORWARD(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, media = true),
    NEXT(KeyEvent.KEYCODE_MEDIA_NEXT, media = true),
    PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS, media = true),
    ;

    companion object {
        fun named(name: String): RemoteKey? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** `/api/volume/{name}` commands: the system music volume, whichever app is in front. */
enum class VolumeCommand {
    UP, DOWN, MUTE;

    companion object {
        fun named(name: String): VolumeCommand? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * The remote's HTTP API. Everything under `/api` except pairing needs a paired token,
 * sent as `Authorization: Bearer <token>` or the `candle_token` cookie.
 */
class RemoteApi(
    private val pairing: Pairing,
    private val device: RemoteDevice,
    private val library: RemoteLibrary,
    /** What is playing, and the clock its report times use (elapsed realtime on the device). */
    private val nowPlaying: () -> PlaybackSnapshot?,
    private val clock: () -> Long,
    /** Static files of the phone page by path ("index.html"), or null. */
    private val staticFile: (String) -> ByteArray? = { null },
) {
    val router = Router().apply {
        get("/ping") { _, _ -> ok("app" to "candle") }
        post("/api/pair/start") { _, _ ->
            device.showPin(pairing.start())
            HttpResponse.json(202, Json.obj("pin" to "shown on the projector"))
        }
        post("/api/pair") { request, _ -> pair(request.form()["pin"].orEmpty()) }
        post("/api/key/{name}") { request, params -> authorized(request) { key(params.getValue("name")) } }
        post("/api/volume/{name}") { request, params -> authorized(request) { volume(params.getValue("name")) } }
        post("/api/home") { request, _ -> authorized(request) { device.goHome(); ok() } }
        get("/api/now-playing") { request, _ ->
            authorized(request) { HttpResponse.json(200, nowPlayingJson(nowPlaying(), clock())) }
        }
        get("/api/sources") { request, _ -> authorized(request) { sources() } }
        get("/api/browse") { request, _ -> authorized(request) { browse(request.query["source"].orEmpty(), request.query["path"].orEmpty()) } }
        post("/api/play") { request, _ -> authorized(request) { play(request.form()) } }
        fallback { request -> static(request.path) }
    }

    fun handle(request: HttpRequest): HttpResponse = router.handle(request)

    private fun pair(pin: String): HttpResponse = when (val result = pairing.pair(pin)) {
        is PairResult.Paired -> ok("token" to result.token)
            .withHeader("Set-Cookie", "$COOKIE=${result.token}; Max-Age=$COOKIE_MAX_AGE; Path=/; HttpOnly; SameSite=Strict")
        PairResult.WrongPin -> HttpResponse.error(403, "wrong PIN")
        PairResult.NoPin -> HttpResponse.error(410, "no PIN on the projector; start pairing again")
    }

    private fun key(name: String): HttpResponse {
        val key = RemoteKey.named(name) ?: return HttpResponse.error(404, "unknown key: $name")
        if (device.pressKey(key.keyCode)) return ok()
        if (!key.media) return HttpResponse.error(409, "Candle is not in front")
        device.mediaKey(key.keyCode)
        return ok()
    }

    private fun volume(name: String): HttpResponse {
        val command = VolumeCommand.named(name) ?: return HttpResponse.error(404, "unknown volume command: $name")
        device.volume(command)
        return ok()
    }

    private fun sources(): HttpResponse {
        val items = library.sources().map { source ->
            Json.obj("id" to source.id, "name" to source.displayName, "kind" to source.id.substringBefore(':'))
        }
        return ok("sources" to Json.array(items))
    }

    private fun browse(sourceId: String, path: String): HttpResponse {
        val source = library.source(sourceId) ?: return HttpResponse.error(404, "unknown source: $sourceId")
        val items = try {
            source.list(path).browsable(PLAYABLE)
        } catch (e: Exception) {
            return HttpResponse.error(502, "cannot read this folder: ${e.message ?: e.javaClass.simpleName}")
        }
        return ok(
            "items" to Json.array(
                items.map { Json.obj("name" to it.name, "path" to it.path, "type" to it.type.name.lowercase(), "size" to it.size) },
            ),
        )
    }

    /** Form fields `source`, `path`, `name` and `type`, as `/api/browse` listed them. */
    private fun play(form: Map<String, String>): HttpResponse {
        val sourceId = form["source"].orEmpty()
        val source = library.source(sourceId) ?: return HttpResponse.error(404, "unknown source: $sourceId")
        val type = MediaType.entries.firstOrNull { it.name.equals(form["type"], ignoreCase = true) }
        if (type != MediaType.VIDEO && type != MediaType.AUDIO) return HttpResponse.error(400, "only videos and music can be played")
        val item = MediaItem(form["name"].orEmpty(), form["path"].orEmpty(), type)
        val started = try {
            library.play(source, item)
        } catch (e: IllegalArgumentException) {
            return HttpResponse.error(400, e.message ?: "cannot play this item")
        }
        return if (started) ok() else HttpResponse.error(409, "Candle cannot play from this source")
    }

    private fun authorized(request: HttpRequest, action: () -> HttpResponse): HttpResponse =
        if (pairing.isPaired(token(request))) action() else HttpResponse.error(401, "pair this phone first")

    private fun token(request: HttpRequest): String? =
        request.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: request.cookie(COOKIE)

    private fun static(path: String): HttpResponse? {
        val name = path.trimStart('/').ifEmpty { "index.html" }
        if (".." in name) return null
        val bytes = staticFile(name) ?: return null
        return HttpResponse(200, contentType(name), bytes)
    }

    private fun ok(vararg fields: Pair<String, Any?>) = HttpResponse.json(200, Json.obj("ok" to true, *fields))

    companion object {
        const val PORT = 8765

        /** The page's address on the projector's network. */
        fun address(ip: String) = "http://$ip:$PORT/"

        /** `/api/now-playing`: state idle, playing or paused, with the position projected to [now]. */
        fun nowPlayingJson(snapshot: PlaybackSnapshot?, now: Long): String =
            if (snapshot == null) {
                Json.obj("state" to "idle")
            } else {
                Json.obj(
                    "state" to if (snapshot.playing) "playing" else "paused",
                    "kind" to snapshot.kind.name.lowercase(),
                    "title" to snapshot.title,
                    "positionMs" to snapshot.positionAt(now),
                    "durationMs" to snapshot.durationMs,
                )
            }
        const val COOKIE = "candle_token"
        private const val COOKIE_MAX_AGE = 10 * 365 * 24 * 60 * 60

        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "json" -> "application/json; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
