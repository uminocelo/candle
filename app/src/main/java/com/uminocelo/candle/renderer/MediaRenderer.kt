package com.uminocelo.candle.renderer

import android.util.Log
import com.uminocelo.candle.dlna.Upnp
import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import com.uminocelo.candle.http.Router
import com.uminocelo.candle.player.PlaybackSnapshot
import com.uminocelo.candle.upnp.Gena
import com.uminocelo.candle.upnp.Soap
import com.uminocelo.candle.upnp.SoapCall
import com.uminocelo.candle.upnp.Subscription
import com.uminocelo.candle.upnp.Subscriptions
import com.uminocelo.candle.upnp.UpnpError
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The projector side of the renderer; [AndroidRendererPlayer] on the device, a fake in tests.
 * Songs open in the video player too: the music queue only reads local folders.
 */
interface RendererPlayer {
    /** Opens [url] in the video player. */
    fun play(url: String, title: String)

    fun resume()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Closes the video player. */
    fun stop()

    /** What the video player reports, or null when none is open. */
    fun snapshot(): PlaybackSnapshot?

    /** The projector's volume, 0–100. */
    fun volume(): Int

    fun setVolume(percent: Int)

    fun isMuted(): Boolean

    fun setMuted(muted: Boolean)

    /** Tells the people in the room that a video from a phone did not open. */
    fun showError(title: String, reason: String)
}

/**
 * A UPnP MediaRenderer: phone apps (BubbleUPnP, VLC, Web Video Caster) find it over
 * SSDP, set a URL with `SetAVTransportURI`, start it with `Play`, then pause, seek and
 * change the volume, following the state through GENA events. Routes live under
 * `/dlna/` on the phone remote's server. [clock] is elapsed-realtime ms, like [PlaybackSnapshot.reportedAt].
 */
class MediaRenderer(
    private val uuid: String,
    private val player: RendererPlayer,
    private val clock: () -> Long,
    private val name: String = "Candle",
    /** Why a URL cannot play, or null; [checkUrl] on the device. */
    private val check: (String) -> String? = ::checkUrl,
    /** Delivers one event; [Gena.send] on the device. */
    private val sendEvent: (callback: URI, sid: String, seq: Long, body: String) -> Boolean = Gena::send,
) : AutoCloseable {
    private class Media(val url: String, val title: String, val metadata: String)

    private var media: Media? = null

    /** When Play last opened the player, until it reports; null otherwise. */
    private var playRequestedAt: Long? = null

    private val subscriptions = RendererDescription.SERVICES.keys.associateWith { Subscriptions(clock) }

    /** The event variables last sent per service; only [tick] touches it. */
    private val lastSent = mutableMapOf<String, Map<String, String>>()
    private var events: ScheduledExecutorService? = null

    val router = Router().apply {
        get(RendererDescription.DEVICE_PATH) { _, _ -> xml(200, RendererDescription.device(uuid, name)) }
        get("/dlna/{file}") { _, params ->
            RendererDescription.scpd(params.getValue("file").removeSuffix(".xml"))?.let { xml(200, it) }
                ?: HttpResponse.error(404, "not found")
        }
        post("/dlna/control/{service}") { request, params -> control(params.getValue("service"), request) }
        route("SUBSCRIBE", "/dlna/event/{service}") { request, params -> subscribe(params.getValue("service"), request) }
        route("UNSUBSCRIBE", "/dlna/event/{service}") { request, params -> unsubscribe(params.getValue("service"), request) }
    }

    fun handle(request: HttpRequest): HttpResponse = router.handle(request)

    /** Starts sending events to subscribed phones. */
    fun start() {
        events = Executors.newSingleThreadScheduledExecutor { Thread(it, "candle-gena").apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay({
                try {
                    tick()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "event tick failed", e)
                }
            }, EVENT_INTERVAL_MS, EVENT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Forgets every subscription, e.g. when the renderer is switched off. */
    fun clearSubscriptions() = subscriptions.values.forEach { it.clear() }

    override fun close() {
        events?.shutdownNow()
        events = null
        clearSubscriptions()
    }

    // ---- Control

    private fun control(service: String, request: HttpRequest): HttpResponse {
        val serviceType = RendererDescription.SERVICES[service] ?: return HttpResponse.error(404, "unknown service: $service")
        return try {
            val call = Soap.parse(request.headers["soapaction"], request.body.toString(Charsets.UTF_8))
            if (call.serviceType != serviceType) throw UpnpError(401, "Invalid Action: ${call.serviceType} sent to $service")
            xml(200, Soap.response(call, perform(call)))
        } catch (e: UpnpError) {
            xml(500, Soap.fault(e))
        }
    }

    private fun perform(call: SoapCall): List<Pair<String, String>> {
        if (call.serviceType != RendererDescription.CONNECTION_MANAGER) checkInstance(call)
        return when (call.serviceType to call.action) {
            RendererDescription.AV_TRANSPORT to "SetAVTransportURI" -> setUri(call)
            RendererDescription.AV_TRANSPORT to "Play" -> play()
            RendererDescription.AV_TRANSPORT to "Pause" -> pause()
            RendererDescription.AV_TRANSPORT to "Stop" -> stop()
            RendererDescription.AV_TRANSPORT to "Seek" -> seek(call)
            RendererDescription.AV_TRANSPORT to "GetTransportInfo" ->
                listOf("CurrentTransportState" to transport().name, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            RendererDescription.AV_TRANSPORT to "GetPositionInfo" -> positionInfo()
            RendererDescription.AV_TRANSPORT to "GetMediaInfo" -> mediaInfo()
            RendererDescription.AV_TRANSPORT to "GetCurrentTransportActions" -> listOf("Actions" to transport().actions)
            RendererDescription.AV_TRANSPORT to "GetDeviceCapabilities" ->
                listOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
            RendererDescription.AV_TRANSPORT to "GetTransportSettings" ->
                listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            RendererDescription.RENDERING_CONTROL to "GetVolume" -> listOf("CurrentVolume" to player.volume().toString())
            RendererDescription.RENDERING_CONTROL to "SetVolume" -> setVolume(call)
            RendererDescription.RENDERING_CONTROL to "GetMute" -> listOf("CurrentMute" to if (player.isMuted()) "1" else "0")
            RendererDescription.RENDERING_CONTROL to "SetMute" -> setMute(call)
            RendererDescription.CONNECTION_MANAGER to "GetProtocolInfo" ->
                listOf("Source" to "", "Sink" to RendererDescription.SINK_PROTOCOLS)
            RendererDescription.CONNECTION_MANAGER to "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
            RendererDescription.CONNECTION_MANAGER to "GetCurrentConnectionInfo" -> listOf(
                "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
            )
            else -> throw UpnpError(401, "Invalid Action: ${call.action}")
        }
    }

    private fun setUri(call: SoapCall): List<Pair<String, String>> {
        val url = call.arg("CurrentURI").trim()
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") throw UpnpError(716, "Resource not found: only http and https URLs play")
        val metadata = call.args["CurrentURIMetaData"].orEmpty()
        // Bad metadata should not stop the video: the title then comes from the URL.
        val title = try {
            Upnp.itemTitle(metadata)
        } catch (e: IOException) {
            null
        }
        val next = Media(url, title ?: titleFromUrl(url), metadata)
        check(url)?.let { reason ->
            player.showError(next.title, reason)
            throw UpnpError(716, "Resource not found: $reason")
        }
        synchronized(this) {
            // A new URL while a video plays replaces it at once, as UPnP asks.
            val wasPlaying = transport() == TransportState.PLAYING
            media = next
            playRequestedAt = null
            if (wasPlaying) open(next)
        }
        return emptyList()
    }

    private fun play(): List<Pair<String, String>> = synchronized(this) {
        val current = media ?: throw UpnpError(701, "Transition not available: no URL set")
        when (transport()) {
            TransportState.PAUSED_PLAYBACK -> player.resume()
            TransportState.STOPPED -> open(current)
            else -> Unit // already playing or opening
        }
        emptyList()
    }

    private fun open(media: Media) {
        playRequestedAt = clock()
        player.play(media.url, media.title)
    }

    private fun pause(): List<Pair<String, String>> {
        when (transport()) {
            TransportState.PLAYING -> player.pause()
            TransportState.PAUSED_PLAYBACK -> Unit
            else -> throw UpnpError(701, "Transition not available: nothing is playing")
        }
        return emptyList()
    }

    private fun stop(): List<Pair<String, String>> = synchronized(this) {
        when (transport()) {
            TransportState.PLAYING, TransportState.PAUSED_PLAYBACK, TransportState.TRANSITIONING -> player.stop()
            else -> Unit
        }
        playRequestedAt = null
        emptyList()
    }

    private fun seek(call: SoapCall): List<Pair<String, String>> {
        val unit = call.arg("Unit")
        if (unit != "REL_TIME" && unit != "ABS_TIME") throw UpnpError(710, "Seek mode not supported: $unit")
        val target = RendererState.parseTime(call.arg("Target")) ?: throw UpnpError(711, "Illegal seek target: ${call.arg("Target")}")
        val snapshot = ownSnapshot() ?: throw UpnpError(701, "Transition not available: nothing is playing")
        player.seekTo(if (snapshot.durationMs > 0) target.coerceAtMost(snapshot.durationMs) else target)
        return emptyList()
    }

    private fun positionInfo(): List<Pair<String, String>> {
        val current = synchronized(this) { media }
        val snapshot = ownSnapshot()
        val position = RendererState.formatTime(snapshot?.positionAt(clock()) ?: 0)
        return listOf(
            "Track" to if (current == null) "0" else "1",
            "TrackDuration" to RendererState.formatTime(snapshot?.durationMs ?: 0),
            "TrackMetaData" to current?.metadata.orEmpty(),
            "TrackURI" to current?.url.orEmpty(),
            "RelTime" to position,
            "AbsTime" to position,
            "RelCount" to Int.MAX_VALUE.toString(),
            "AbsCount" to Int.MAX_VALUE.toString(),
        )
    }

    private fun mediaInfo(): List<Pair<String, String>> {
        val current = synchronized(this) { media }
        return listOf(
            "NrTracks" to if (current == null) "0" else "1",
            "MediaDuration" to RendererState.formatTime(ownSnapshot()?.durationMs ?: 0),
            "CurrentURI" to current?.url.orEmpty(),
            "CurrentURIMetaData" to current?.metadata.orEmpty(),
            "NextURI" to "",
            "NextURIMetaData" to "",
            "PlayMedium" to "NETWORK",
            "RecordMedium" to "NOT_IMPLEMENTED",
            "WriteStatus" to "NOT_IMPLEMENTED",
        )
    }

    private fun setVolume(call: SoapCall): List<Pair<String, String>> {
        val percent = call.arg("DesiredVolume").trim().toIntOrNull()?.takeIf { it in 0..100 }
            ?: throw UpnpError(402, "Invalid Args: DesiredVolume must be 0–100")
        player.setVolume(percent)
        return emptyList()
    }

    private fun setMute(call: SoapCall): List<Pair<String, String>> {
        val muted = when (call.arg("DesiredMute").trim().lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> throw UpnpError(402, "Invalid Args: DesiredMute must be 0 or 1")
        }
        player.setMuted(muted)
        return emptyList()
    }

    private fun checkInstance(call: SoapCall) {
        if (call.arg("InstanceID").trim() != "0") throw UpnpError(718, "Invalid InstanceID")
    }

    // ---- State

    private fun transport(): TransportState = synchronized(this) {
        RendererState.transportState(media?.url, player.snapshot(), playRequestedAt, clock())
    }

    /** The player's report while it plays this renderer's URL; another video on screen is not ours. */
    private fun ownSnapshot(): PlaybackSnapshot? {
        val url = synchronized(this) { media?.url } ?: return null
        return player.snapshot()?.takeIf { it.uri == url }
    }

    /** The evented variables of [service], in a stable order. */
    private fun eventVars(service: String): Map<String, String> = when (service) {
        "AVTransport" -> {
            val current = synchronized(this) { media }
            val duration = RendererState.formatTime(ownSnapshot()?.durationMs ?: 0)
            val state = transport()
            linkedMapOf(
                "TransportState" to state.name,
                "TransportStatus" to "OK",
                "CurrentTransportActions" to state.actions,
                "NumberOfTracks" to if (current == null) "0" else "1",
                "AVTransportURI" to current?.url.orEmpty(),
                "CurrentTrackURI" to current?.url.orEmpty(),
                "AVTransportURIMetaData" to current?.metadata.orEmpty(),
                "CurrentTrackMetaData" to current?.metadata.orEmpty(),
                "CurrentTrackDuration" to duration,
                "CurrentMediaDuration" to duration,
            )
        }
        "RenderingControl" -> linkedMapOf("Volume" to player.volume().toString(), "Mute" to if (player.isMuted()) "1" else "0")
        else -> linkedMapOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to RendererDescription.SINK_PROTOCOLS, "CurrentConnectionIDs" to "0")
    }

    /** ConnectionManager events its variables directly; the others wrap them in `LastChange`. */
    private fun eventBody(service: String, vars: Map<String, String>): String = when (service) {
        "AVTransport" -> RendererState.propertySet(mapOf("LastChange" to RendererState.lastChange(RendererState.AVT_EVENT_NS, vars)))
        "RenderingControl" -> RendererState.propertySet(mapOf("LastChange" to RendererState.lastChange(RendererState.RCS_EVENT_NS, vars)))
        else -> RendererState.propertySet(vars)
    }

    // ---- Events

    private fun subscribe(service: String, request: HttpRequest): HttpResponse {
        val subs = subscriptions[service] ?: return HttpResponse.error(404, "unknown service: $service")
        val sid = request.headers["sid"]
        val timeout = Gena.timeout(request.headers["timeout"])
        if (sid != null) {
            if (request.headers["nt"] != null || request.headers["callback"] != null) return HttpResponse.error(400, "SID with NT or CALLBACK")
            val renewed = subs.renew(sid, timeout) ?: return HttpResponse.error(412, "unknown or expired SID")
            return subscribed(renewed.sid, timeout)
        }
        val callbacks = Gena.callbacks(request.headers["callback"])
        if (request.headers["nt"] != "upnp:event" || callbacks.isEmpty()) return HttpResponse.error(412, "NT upnp:event and an http CALLBACK are required")
        // The first event (the full state) goes out on the next tick, after this answer.
        return subscribed(subs.subscribe(callbacks, timeout).sid, timeout)
    }

    private fun subscribed(sid: String, timeout: Long) =
        HttpResponse(200, "text/plain", ByteArray(0), mapOf("SID" to sid, "TIMEOUT" to "Second-${Subscriptions.grant(timeout)}", "Server" to SERVER))

    private fun unsubscribe(service: String, request: HttpRequest): HttpResponse {
        val subs = subscriptions[service] ?: return HttpResponse.error(404, "unknown service: $service")
        val sid = request.headers["sid"] ?: return HttpResponse.error(412, "SID is required")
        return if (subs.unsubscribe(sid)) HttpResponse(200, "text/plain", ByteArray(0)) else HttpResponse.error(412, "unknown or expired SID")
    }

    /**
     * Sends new subscribers the full state and everyone else what changed since the last
     * tick. Runs every [EVENT_INTERVAL_MS] on the event thread; tests call it directly.
     */
    fun tick() {
        for ((service, subs) in subscriptions) {
            val live = subs.current()
            if (live.isEmpty()) {
                lastSent.remove(service)
                continue
            }
            val now = eventVars(service)
            val previous = lastSent[service]
            val changed = if (previous == null) now else now.filter { (name, value) -> previous[name] != value }
            lastSent[service] = now
            for (subscription in live) {
                when {
                    subscription.seq == 0L -> deliver(subscription, eventBody(service, now))
                    changed.isNotEmpty() -> deliver(subscription, eventBody(service, changed))
                }
            }
        }
    }

    /** Tries each callback URL in order until one takes the event. */
    private fun deliver(subscription: Subscription, body: String) {
        val seq = subscription.nextSeq()
        subscription.callbacks.firstOrNull { sendEvent(it, subscription.sid, seq, body) }
    }

    private fun xml(status: Int, body: String) =
        HttpResponse(status, "text/xml; charset=\"utf-8\"", body.toByteArray(), mapOf("EXT" to "", "Server" to SERVER))

    companion object {
        const val SERVER = "Android/11 UPnP/1.0 Candle/1.0"
        private const val TAG = "CandleRenderer"
        const val EVENT_INTERVAL_MS = 500L

        /** `http://host/films/My%20Film.mkv?token=1` → "My Film". */
        fun titleFromUrl(url: String): String {
            val file = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            val decoded = runCatching { URLDecoder.decode(file, "UTF-8") }.getOrDefault(file)
            return decoded.substringBeforeLast('.').ifBlank { "Video from phone" }
        }
    }
}
