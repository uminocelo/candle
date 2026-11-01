package com.uminocelo.candle.renderer

import com.uminocelo.candle.upnp.Scpd
import com.uminocelo.candle.upnp.Soap
import com.uminocelo.candle.upnp.SsdpDevice

/**
 * The renderer's UPnP device description and service descriptions (SCPDs), served
 * under `/dlna/`. The SCPDs list only the actions [MediaRenderer] implements.
 */
object RendererDescription {
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    /** Short service name in URLs (`/dlna/control/AVTransport`) → service type. */
    val SERVICES = mapOf(
        "AVTransport" to AV_TRANSPORT,
        "RenderingControl" to RENDERING_CONTROL,
        "ConnectionManager" to CONNECTION_MANAGER,
    )

    const val DEVICE_PATH = "/dlna/device.xml"

    /** How the renderer announces itself over SSDP. */
    fun ssdpDevice(uuid: String) = SsdpDevice(uuid, MEDIA_RENDERER, SERVICES.values.toList(), MediaRenderer.SERVER)

    /** What the players open: anything over HTTP, and the common types spelled out for picky control points. */
    val SINK_PROTOCOLS = listOf(
        "video/mp4", "video/x-matroska", "video/webm", "video/quicktime", "video/x-msvideo", "video/mpeg",
        "video/mp2t", "video/3gpp", "video/x-flv", "application/vnd.apple.mpegurl", "application/x-mpegURL",
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac", "audio/ogg", "audio/wav", "audio/x-wav",
        "*",
    ).joinToString(",") { "http-get:*:$it:*" }

    fun device(uuid: String, name: String): String {
        val services = SERVICES.entries.joinToString("") { (short, type) ->
            "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$short</serviceId>" +
                "<SCPDURL>/dlna/$short.xml</SCPDURL><controlURL>/dlna/control/$short</controlURL>" +
                "<eventSubURL>/dlna/event/$short</eventSubURL></service>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>$MEDIA_RENDERER</deviceType>
<friendlyName>${Soap.escape(name)}</friendlyName>
<manufacturer>Candle</manufacturer>
<modelName>Candle</modelName>
<modelDescription>Candle on the projector</modelDescription>
<UDN>uuid:$uuid</UDN>
<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
<serviceList>$services</serviceList>
</device>
</root>"""
    }

    /** The SCPD for a short service name, or null for an unknown one. */
    fun scpd(service: String): String? = when (service) {
        "AVTransport" -> Scpd.document(
            actions = listOf(
                Scpd.action("SetAVTransportURI", instanceId, "CurrentURI" to "AVTransportURI", "CurrentURIMetaData" to "AVTransportURIMetaData"),
                Scpd.action("Play", instanceId, "Speed" to "TransportPlaySpeed"),
                Scpd.action("Pause", instanceId),
                Scpd.action("Stop", instanceId),
                Scpd.action("Seek", instanceId, "Unit" to "A_ARG_TYPE_SeekMode", "Target" to "A_ARG_TYPE_SeekTarget"),
                Scpd.action(
                    "GetTransportInfo", instanceId,
                    out = listOf("CurrentTransportState" to "TransportState", "CurrentTransportStatus" to "TransportStatus", "CurrentSpeed" to "TransportPlaySpeed"),
                ),
                Scpd.action(
                    "GetPositionInfo", instanceId,
                    out = listOf(
                        "Track" to "CurrentTrack", "TrackDuration" to "CurrentTrackDuration", "TrackMetaData" to "CurrentTrackMetaData",
                        "TrackURI" to "CurrentTrackURI", "RelTime" to "RelativeTimePosition", "AbsTime" to "AbsoluteTimePosition",
                        "RelCount" to "RelativeCounterPosition", "AbsCount" to "AbsoluteCounterPosition",
                    ),
                ),
                Scpd.action(
                    "GetMediaInfo", instanceId,
                    out = listOf(
                        "NrTracks" to "NumberOfTracks", "MediaDuration" to "CurrentMediaDuration", "CurrentURI" to "AVTransportURI",
                        "CurrentURIMetaData" to "AVTransportURIMetaData", "NextURI" to "NextAVTransportURI",
                        "NextURIMetaData" to "NextAVTransportURIMetaData", "PlayMedium" to "PlaybackStorageMedium",
                        "RecordMedium" to "RecordStorageMedium", "WriteStatus" to "RecordMediumWriteStatus",
                    ),
                ),
                Scpd.action("GetCurrentTransportActions", instanceId, out = listOf("Actions" to "CurrentTransportActions")),
                Scpd.action(
                    "GetDeviceCapabilities", instanceId,
                    out = listOf("PlayMedia" to "PossiblePlaybackStorageMedia", "RecMedia" to "PossibleRecordStorageMedia", "RecQualityModes" to "PossibleRecordQualityModes"),
                ),
                Scpd.action("GetTransportSettings", instanceId, out = listOf("PlayMode" to "CurrentPlayMode", "RecQualityMode" to "CurrentRecordQualityMode")),
            ),
            variables = listOf(
                Scpd.variable("A_ARG_TYPE_InstanceID", "ui4"),
                Scpd.variable("AVTransportURI", "string"),
                Scpd.variable("AVTransportURIMetaData", "string"),
                Scpd.variable("NextAVTransportURI", "string"),
                Scpd.variable("NextAVTransportURIMetaData", "string"),
                Scpd.variable("TransportPlaySpeed", "string", allowed = listOf("1")),
                Scpd.variable("TransportState", "string", allowed = TransportState.entries.map { it.name }),
                Scpd.variable("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
                Scpd.variable("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME")),
                Scpd.variable("A_ARG_TYPE_SeekTarget", "string"),
                Scpd.variable("CurrentTrack", "ui4"),
                Scpd.variable("NumberOfTracks", "ui4"),
                Scpd.variable("CurrentTrackDuration", "string"),
                Scpd.variable("CurrentMediaDuration", "string"),
                Scpd.variable("CurrentTrackMetaData", "string"),
                Scpd.variable("CurrentTrackURI", "string"),
                Scpd.variable("RelativeTimePosition", "string"),
                Scpd.variable("AbsoluteTimePosition", "string"),
                Scpd.variable("RelativeCounterPosition", "i4"),
                Scpd.variable("AbsoluteCounterPosition", "i4"),
                Scpd.variable("CurrentTransportActions", "string"),
                Scpd.variable("PlaybackStorageMedium", "string"),
                Scpd.variable("RecordStorageMedium", "string"),
                Scpd.variable("RecordMediumWriteStatus", "string"),
                Scpd.variable("PossiblePlaybackStorageMedia", "string"),
                Scpd.variable("PossibleRecordStorageMedia", "string"),
                Scpd.variable("PossibleRecordQualityModes", "string"),
                Scpd.variable("CurrentPlayMode", "string", allowed = listOf("NORMAL")),
                Scpd.variable("CurrentRecordQualityMode", "string"),
                Scpd.variable("LastChange", "string", events = true),
            ),
        )
        "ConnectionManager" -> Scpd.CONNECTION_MANAGER
        "RenderingControl" -> Scpd.document(
            actions = listOf(
                Scpd.action("GetVolume", instanceId, channelArg, out = listOf("CurrentVolume" to "Volume")),
                Scpd.action("SetVolume", instanceId, channelArg, "DesiredVolume" to "Volume"),
                Scpd.action("GetMute", instanceId, channelArg, out = listOf("CurrentMute" to "Mute")),
                Scpd.action("SetMute", instanceId, channelArg, "DesiredMute" to "Mute"),
            ),
            variables = listOf(
                Scpd.variable("A_ARG_TYPE_InstanceID", "ui4"),
                Scpd.variable("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
                Scpd.variable("Volume", "ui2", range = 0..100),
                Scpd.variable("Mute", "boolean"),
                Scpd.variable("LastChange", "string", events = true),
            ),
        )
        else -> null
    }

    private val instanceId = "InstanceID" to "A_ARG_TYPE_InstanceID"
    private val channelArg = "Channel" to "A_ARG_TYPE_Channel"
}
