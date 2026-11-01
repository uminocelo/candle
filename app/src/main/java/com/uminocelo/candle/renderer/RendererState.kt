package com.uminocelo.candle.renderer

import com.uminocelo.candle.player.PlaybackSnapshot
import com.uminocelo.candle.upnp.Soap

/** AVTransport's `TransportState` values. */
enum class TransportState(val actions: String) {
    STOPPED("Play"),
    PLAYING("Pause,Stop,Seek"),
    PAUSED_PLAYBACK("Play,Stop,Seek"),

    /** Play was sent and the player is still opening the URL. */
    TRANSITIONING("Stop"),
    NO_MEDIA_PRESENT(""),
}

/** Turns what the video player reports into UPnP state, times and event bodies. */
object RendererState {
    /** How long after Play a player that has not reported yet still counts as opening. */
    const val OPENING_MS = 15_000L

    /**
     * The state for the URL set with SetAVTransportURI ([url], null when none). [snapshot] is
     * what the video player reports; it is about this URL only when it plays the same one.
     */
    fun transportState(url: String?, snapshot: PlaybackSnapshot?, playRequestedAt: Long?, now: Long): TransportState = when {
        url == null -> TransportState.NO_MEDIA_PRESENT
        snapshot != null && snapshot.uri == url ->
            if (snapshot.playWhenReady) TransportState.PLAYING else TransportState.PAUSED_PLAYBACK
        playRequestedAt != null && now - playRequestedAt < OPENING_MS -> TransportState.TRANSITIONING
        else -> TransportState.STOPPED
    }

    /** 83_500 ms → "0:01:23", the UPnP H+:MM:SS form. */
    fun formatTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    /** "0:01:23", "00:01:23.500" or "01:23" → ms; null when it is not a time. */
    fun parseTime(text: String): Long? {
        val match = Regex("""(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:\.(\d+))?""").matchEntire(text.trim()) ?: return null
        val (hours, minutes, seconds, fraction) = match.destructured
        if (minutes.toInt() > 59 || seconds.toInt() > 59) return null
        val millis = fraction.padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
        return ((hours.ifEmpty { "0" }.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1000 + millis
    }

    const val AVT_EVENT_NS = "urn:schemas-upnp-org:metadata-1-0/AVT/"
    const val RCS_EVENT_NS = "urn:schemas-upnp-org:metadata-1-0/RCS/"

    /**
     * The `LastChange` value: `<Event><InstanceID val="0"><TransportState val="PLAYING"/>…`.
     * RenderingControl variables carry `channel="Master"`.
     */
    fun lastChange(namespace: String, vars: Map<String, String>): String {
        val channel = if (namespace == RCS_EVENT_NS) " channel=\"Master\"" else ""
        val body = vars.entries.joinToString("") { (name, value) -> "<$name$channel val=\"${Soap.escape(value)}\"/>" }
        return "<Event xmlns=\"$namespace\"><InstanceID val=\"0\">$body</InstanceID></Event>"
    }

    /** A GENA NOTIFY body with one property per entry. */
    fun propertySet(properties: Map<String, String>): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            properties.entries.joinToString("") { (name, value) -> "<e:property><$name>${Soap.escape(value)}</$name></e:property>" } +
            "</e:propertyset>"
}
