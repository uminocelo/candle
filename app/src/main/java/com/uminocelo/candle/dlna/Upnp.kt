package com.uminocelo.candle.dlna

import com.uminocelo.candle.media.MediaType
import com.uminocelo.candle.upnp.Xml
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.IOException
import java.net.URI

/**
 * Parsing for the small part of UPnP Candle uses: SSDP replies, the device
 * description, and ContentDirectory `Browse` responses with their DIDL-Lite payload.
 * Plain JVM XML (`javax.xml`), so it runs in unit tests. Malformed input throws
 * [IOException].
 */
object Upnp {
    const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"

    /** The `LOCATION` header of an SSDP reply (the device description URL), or null. */
    fun ssdpLocation(reply: String): String? =
        reply.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** A media server's name and ContentDirectory control URL from its description at [location]. */
    fun device(location: String, xml: String): DlnaServer {
        val doc = parse(xml)
        val device = doc.firstByName("device") ?: throw IOException("no device in $location")
        val udn = device.childText("UDN") ?: throw IOException("no UDN in $location")
        val name = device.childText("friendlyName") ?: udn
        val base = doc.firstByName("URLBase")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val control = doc.byName("service")
            .firstOrNull { it.childText("serviceType")?.startsWith(CONTENT_DIRECTORY.dropLast(1)) == true }
            ?.childText("controlURL")
            ?: throw IOException("$name has no ContentDirectory service")
        return DlnaServer(udn, name, resolve(base, control))
    }

    /** The DIDL-Lite document and paging counts of a SOAP `BrowseResponse`. */
    fun browseResponse(xml: String): BrowsePage {
        val doc = parse(xml)
        doc.firstByName("Fault")?.let { fault ->
            val detail = fault.firstByName("errorDescription")?.textContent ?: fault.firstByName("faultstring")?.textContent
            throw IOException("Browse failed: ${detail?.trim() ?: "SOAP fault"}")
        }
        val result = doc.firstByName("Result")?.textContent ?: throw IOException("no Result in Browse response")
        return BrowsePage(
            entries = didl(result),
            returned = doc.firstByName("NumberReturned")?.textContent?.trim()?.toIntOrNull() ?: 0,
            total = doc.firstByName("TotalMatches")?.textContent?.trim()?.toIntOrNull() ?: 0,
        )
    }

    /** Containers and items of a DIDL-Lite document, in document order. */
    fun didl(xml: String): List<DidlEntry> {
        if (xml.isBlank()) return emptyList()
        val root = parse(xml).documentElement
        return root.childElements().mapNotNull { element ->
            val id = element.getAttribute("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = element.childText("title") ?: id
            when (element.localName ?: element.tagName) {
                "container" -> DidlEntry(id, title, MediaType.FOLDER, null, null)
                "item" -> {
                    val type = typeOf(element.childText("class").orEmpty())
                    val resource = bestResource(element.childElements().filter { it.localName == "res" }, type)
                    DidlEntry(id, title, type, resource?.textContent?.trim(), resource?.getAttribute("size")?.toLongOrNull())
                }
                else -> null
            }
        }
    }

    /** The `dc:title` of the item a control point describes in `CurrentURIMetaData` (DIDL-Lite, often empty). */
    fun itemTitle(xml: String): String? =
        if (xml.isBlank()) null else parse(xml).firstByName("item")?.childText("title")

    /**
     * `object.item.videoItem.movie` → VIDEO, and so on. `textItem` is what servers call
     * documents (Candle Server's PDFs and books); their names say which kind.
     */
    fun typeOf(upnpClass: String): MediaType = when {
        upnpClass.startsWith("object.item.videoItem") -> MediaType.VIDEO
        upnpClass.startsWith("object.item.audioItem") -> MediaType.AUDIO
        upnpClass.startsWith("object.item.imageItem") -> MediaType.IMAGE
        upnpClass.startsWith("object.item.textItem") -> MediaType.DOCUMENT
        else -> MediaType.OTHER
    }

    /**
     * Servers list the original file first and transcoded copies after it; take the
     * first HTTP resource whose MIME type matches the item, else the first HTTP one.
     */
    private fun bestResource(resources: List<Element>, type: MediaType): Element? {
        val http = resources.filter { it.getAttribute("protocolInfo").startsWith("http-get:") && !it.textContent.isNullOrBlank() }
        val wanted = when (type) {
            MediaType.VIDEO -> "video/"
            MediaType.AUDIO -> "audio/"
            MediaType.IMAGE -> "image/"
            else -> null
        }
        return http.firstOrNull { wanted != null && it.getAttribute("protocolInfo").split(':').getOrNull(2)?.startsWith(wanted) == true }
            ?: http.firstOrNull()
    }

    private fun resolve(base: String, url: String): String = try {
        URI(base).resolve(url.trim()).toString()
    } catch (e: IllegalArgumentException) {
        throw IOException("bad URL $url (base $base)", e)
    }

    /** Parses XML from the network; malformed input throws [IOException]. */
    fun parse(xml: String): Document = Xml.parse(xml)

    private fun Document.byName(name: String): List<Element> =
        getElementsByTagNameNS("*", name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Document.firstByName(name: String): Element? = byName(name).firstOrNull()

    private fun Element.firstByName(name: String): Element? = getElementsByTagNameNS("*", name).item(0) as Element?

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.childText(name: String): String? =
        childElements().firstOrNull { (it.localName ?: it.tagName) == name }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

/** A discovered media server. [udn] is its stable id; [controlUrl] takes `Browse` requests. */
data class DlnaServer(val udn: String, val name: String, val controlUrl: String)

/** One child of a container: a folder, or an item with the URL the player opens. */
data class DidlEntry(val id: String, val title: String, val type: MediaType, val url: String?, val size: Long?)

data class BrowsePage(val entries: List<DidlEntry>, val returned: Int, val total: Int)
