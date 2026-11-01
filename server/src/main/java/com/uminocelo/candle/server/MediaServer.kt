package com.uminocelo.candle.server

import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import com.uminocelo.candle.upnp.Scpd
import com.uminocelo.candle.upnp.Soap
import com.uminocelo.candle.upnp.SoapCall
import com.uminocelo.candle.upnp.SsdpDevice
import com.uminocelo.candle.upnp.UpnpError
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Makes small JPEG previews of photos and videos; null when a file has none. */
fun interface Thumbnails {
    fun jpeg(item: IndexEntry.Item): File?
}

/**
 * A UPnP MediaServer over the [index]: the device description, ContentDirectory
 * `Browse`, ConnectionManager, and the files and thumbnails themselves, all under
 * `/dlna/`. Players cannot send a password, so the whole of it is off (404) unless
 * [enabled] says the user shares with players.
 */
class MediaServer(
    private val uuid: String,
    private val name: () -> String,
    private val index: MediaIndex,
    private val thumbnails: Thumbnails,
    private val enabled: () -> Boolean,
) {
    val ssdpDevice = SsdpDevice(uuid, MEDIA_SERVER, SERVICES.values.toList(), SERVER)

    /** Answers a request under `/dlna/`, or null for any other path. */
    fun handle(request: HttpRequest): HttpResponse? {
        val path = request.path
        if (!path.startsWith(PREFIX)) return null
        if (!enabled()) return HttpResponse.error(404, "sharing with players is off")
        val rest = path.removePrefix(PREFIX)
        val get = request.method == "GET" || request.method == "HEAD"
        return when {
            rest == "device.xml" && get -> xml(200, description())
            rest.startsWith("control/") && request.method == "POST" -> control(rest.removePrefix("control/"), request)
            rest.startsWith("media/") && get -> media(request, rest.removePrefix("media/"))
            rest.startsWith("thumb/") && get -> thumbnail(rest.removePrefix("thumb/"))
            rest.endsWith(".xml") && get -> scpd(rest.removeSuffix(".xml"))?.let { xml(200, it) }
            else -> null
        } ?: HttpResponse.error(404, "not found")
    }

    fun description(): String {
        val services = SERVICES.entries.joinToString("") { (short, type) ->
            "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$short</serviceId>" +
                "<SCPDURL>$PREFIX$short.xml</SCPDURL><controlURL>${PREFIX}control/$short</controlURL>" +
                "<eventSubURL>${PREFIX}event/$short</eventSubURL></service>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>$MEDIA_SERVER</deviceType>
<friendlyName>${Soap.escape(name())}</friendlyName>
<manufacturer>Candle</manufacturer>
<modelName>Candle Server</modelName>
<modelDescription>Films, music, photos and documents from the phone</modelDescription>
<UDN>uuid:$uuid</UDN>
<dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
<serviceList>$services</serviceList>
</device>
</root>"""
    }

    private fun control(service: String, request: HttpRequest): HttpResponse? {
        val serviceType = SERVICES[service] ?: return null
        return try {
            val call = Soap.parse(request.headers["soapaction"], request.body.toString(Charsets.UTF_8))
            if (call.serviceType != serviceType) throw UpnpError(401, "Invalid Action: ${call.serviceType} sent to $service")
            val base = "http://${request.headers["host"] ?: throw UpnpError(402, "Invalid Args: no Host header")}"
            xml(200, Soap.response(call, perform(call, base)))
        } catch (e: UpnpError) {
            xml(500, Soap.fault(e))
        }
    }

    private fun perform(call: SoapCall, base: String): List<Pair<String, String>> {
        if (call.action !in if (call.serviceType == CONTENT_DIRECTORY) CD_ACTIONS else CM_ACTIONS) throw UpnpError(401, "Invalid Action: ${call.action}")
        return answer(call, base)
    }

    private fun answer(call: SoapCall, base: String): List<Pair<String, String>> = when (call.action) {
        "Browse" -> browse(call, base)
        // Scanning first, so a player asking before any Browse still gets the current id.
        "GetSystemUpdateID" -> index.snapshot().let { listOf("Id" to index.updateId.toString()) }
        "GetSearchCapabilities" -> listOf("SearchCaps" to "")
        "GetSortCapabilities" -> listOf("SortCaps" to "")
        "GetProtocolInfo" -> listOf("Source" to FileType.mimes.joinToString(",") { "http-get:*:$it:*" }, "Sink" to "")
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> {
            if (call.arg("ConnectionID").trim() != "0") throw UpnpError(706, "Invalid connection reference")
            listOf(
                "RcsID" to "-1", "AVTransportID" to "-1", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Output", "Status" to "OK",
            )
        }
        else -> throw UpnpError(401, "Invalid Action: ${call.action}")
    }

    /** `Browse`: one entry's metadata, or a page of a folder's children. */
    private fun browse(call: SoapCall, base: String): List<Pair<String, String>> {
        val snapshot = index.snapshot()
        val entry = snapshot[call.arg("ObjectID")] ?: throw UpnpError(701, "No such object: ${call.arg("ObjectID")}")
        val start = call.arg("StartingIndex").trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw UpnpError(402, "Invalid Args: StartingIndex")
        val count = call.arg("RequestedCount").trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw UpnpError(402, "Invalid Args: RequestedCount")
        val (page, total) = when (call.arg("BrowseFlag")) {
            "BrowseMetadata" -> listOf(entry) to 1
            "BrowseDirectChildren" -> {
                val children = (entry as? IndexEntry.Folder)?.children.orEmpty()
                // RequestedCount 0 means "all of them".
                children.drop(start).let { if (count == 0) it else it.take(count) } to children.size
            }
            else -> throw UpnpError(402, "Invalid Args: BrowseFlag")
        }
        return listOf(
            "Result" to didl(page, base),
            "NumberReturned" to page.size.toString(),
            "TotalMatches" to total.toString(),
            "UpdateID" to index.updateId.toString(),
        )
    }

    /** A DIDL-Lite document: folders as storage folders, files with their stream URL, size and thumbnail. */
    fun didl(entries: List<IndexEntry>, base: String): String = buildString {
        append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" """)
        append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">""")
        for (entry in entries) {
            val ids = """id="${Soap.escape(entry.id)}" parentID="${Soap.escape(entry.parentId)}" restricted="1""""
            when (entry) {
                is IndexEntry.Folder -> append(
                    "<container $ids childCount=\"${entry.children.size}\" searchable=\"0\">" +
                        "<dc:title>${Soap.escape(entry.title())}</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>",
                )
                is IndexEntry.Item -> {
                    append("<item $ids><dc:title>${Soap.escape(entry.title())}</dc:title><upnp:class>${upnpClass(entry.type.kind)}</upnp:class>")
                    append("<dc:date>${date(entry.modified)}</dc:date>")
                    val thumb = if (entry.type.kind == MediaKind.VIDEO || entry.type.kind == MediaKind.IMAGE) "$base${PREFIX}thumb/${encode(entry.id)}" else null
                    if (thumb != null) append("<upnp:albumArtURI dlna:profileID=\"JPEG_TN\">${Soap.escape(thumb)}</upnp:albumArtURI>")
                    append("<res protocolInfo=\"http-get:*:${entry.type.mime}:${features(entry.type.kind)}\" size=\"${entry.size}\">")
                    append(Soap.escape("$base${PREFIX}media/${encode(entry.id)}")).append("</res>")
                    if (thumb != null) append("<res protocolInfo=\"http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN\">${Soap.escape(thumb)}</res>")
                    append("</item>")
                }
            }
        }
        append("</DIDL-Lite>")
    }

    private fun media(request: HttpRequest, path: String): HttpResponse? {
        val item = index.snapshot()[path] as? IndexEntry.Item ?: return null
        if (!item.file.isFile) return null
        val headers = mapOf(
            "contentFeatures.dlna.org" to features(item.type.kind),
            "transferMode.dlna.org" to if (item.type.kind == MediaKind.VIDEO || item.type.kind == MediaKind.AUDIO) "Streaming" else "Interactive",
        )
        return HttpResponse.file(request, item.file, item.type.mime, headers)
    }

    private fun thumbnail(path: String): HttpResponse? {
        val item = index.snapshot()[path] as? IndexEntry.Item ?: return null
        val jpeg = thumbnails.jpeg(item) ?: return null
        return HttpResponse(200, "image/jpeg", jpeg.readBytes(), mapOf("Cache-Control" to "max-age=86400"))
    }

    private fun scpd(service: String): String? = when (service) {
        "ContentDirectory" -> Scpd.document(
            actions = listOf(
                Scpd.action(
                    "Browse",
                    "ObjectID" to "A_ARG_TYPE_ObjectID", "BrowseFlag" to "A_ARG_TYPE_BrowseFlag", "Filter" to "A_ARG_TYPE_Filter",
                    "StartingIndex" to "A_ARG_TYPE_Index", "RequestedCount" to "A_ARG_TYPE_Count", "SortCriteria" to "A_ARG_TYPE_SortCriteria",
                    out = listOf("Result" to "A_ARG_TYPE_Result", "NumberReturned" to "A_ARG_TYPE_Count", "TotalMatches" to "A_ARG_TYPE_Count", "UpdateID" to "A_ARG_TYPE_UpdateID"),
                ),
                Scpd.action("GetSystemUpdateID", out = listOf("Id" to "SystemUpdateID")),
                Scpd.action("GetSearchCapabilities", out = listOf("SearchCaps" to "SearchCapabilities")),
                Scpd.action("GetSortCapabilities", out = listOf("SortCaps" to "SortCapabilities")),
            ),
            variables = listOf(
                Scpd.variable("A_ARG_TYPE_ObjectID", "string"),
                Scpd.variable("A_ARG_TYPE_BrowseFlag", "string", allowed = listOf("BrowseMetadata", "BrowseDirectChildren")),
                Scpd.variable("A_ARG_TYPE_Filter", "string"),
                Scpd.variable("A_ARG_TYPE_Index", "ui4"),
                Scpd.variable("A_ARG_TYPE_Count", "ui4"),
                Scpd.variable("A_ARG_TYPE_SortCriteria", "string"),
                Scpd.variable("A_ARG_TYPE_Result", "string"),
                Scpd.variable("A_ARG_TYPE_UpdateID", "ui4"),
                Scpd.variable("SystemUpdateID", "ui4", events = true),
                Scpd.variable("SearchCapabilities", "string"),
                Scpd.variable("SortCapabilities", "string"),
            ),
        )
        "ConnectionManager" -> Scpd.CONNECTION_MANAGER
        else -> null
    }

    private fun xml(status: Int, body: String) = HttpResponse(status, "text/xml; charset=\"utf-8\"", body.toByteArray(Charsets.UTF_8))

    companion object {
        const val PREFIX = "/dlna/"
        const val DEVICE_PATH = "${PREFIX}device.xml"
        const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
        const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
        const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
        const val SERVER = "Android/12 UPnP/1.0 CandleServer/1.0"

        /** Short service name in URLs (`/dlna/control/ContentDirectory`) → service type. */
        val SERVICES = mapOf("ContentDirectory" to CONTENT_DIRECTORY, "ConnectionManager" to CONNECTION_MANAGER)

        private val CD_ACTIONS = setOf("Browse", "GetSystemUpdateID", "GetSearchCapabilities", "GetSortCapabilities")
        private val CM_ACTIONS = setOf("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo")

        /** Byte seeking (OP=01), not transcoded (CI=0), and the streaming/interactive/background transfer flags. */
        fun features(kind: MediaKind): String {
            val flags = if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) "01700000000000000000000000000000" else "00f00000000000000000000000000000"
            return "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$flags"
        }

        fun upnpClass(kind: MediaKind) = when (kind) {
            MediaKind.VIDEO -> "object.item.videoItem"
            MediaKind.AUDIO -> "object.item.audioItem.musicTrack"
            MediaKind.IMAGE -> "object.item.imageItem.photo"
            MediaKind.DOCUMENT -> "object.item.textItem"
        }

        /** An id as URL path segments: each part percent-encoded, slashes kept. */
        fun encode(id: String) = id.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        /** Films and songs show without their extension; photos and documents keep it, as people know them by it. */
        private fun IndexEntry.title(): String = when {
            this is IndexEntry.Item && (type.kind == MediaKind.VIDEO || type.kind == MediaKind.AUDIO) -> name.substringBeforeLast('.')
            else -> name
        }

        private fun date(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
            .apply { timeZone = TimeZone.getDefault() }.format(Date(millis))
    }
}
