package com.uminocelo.candle.dlna

import java.io.IOException
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL

/** SSDP discovery and HTTP for UPnP media servers. Every call blocks; run it off the main thread. */
object DlnaClient {
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val HTTP_TIMEOUT_MS = 5_000
    private const val PAGE_SIZE = 200

    /**
     * Sends an SSDP M-SEARCH for media servers and returns those that answer within
     * [waitMs], with their descriptions fetched. Servers that fail to describe themselves
     * are skipped.
     */
    fun discover(waitMs: Int = 3_000): List<DlnaServer> {
        val locations = linkedSetOf<String>()
        MulticastSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(null)
            socket.timeToLive = 4
            val search = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ${Upnp.MEDIA_SERVER}\r\n\r\n"
                ).toByteArray()
            val group = InetAddress.getByName(SSDP_ADDRESS)
            // UDP can drop a packet; asking twice is cheap.
            repeat(2) { socket.send(DatagramPacket(search, search.size, group, SSDP_PORT)) }
            val deadline = System.currentTimeMillis() + waitMs
            val buffer = ByteArray(2048)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                Upnp.ssdpLocation(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let(locations::add)
            }
        }
        return locations
            .mapNotNull { location -> runCatching { Upnp.device(location, get(location)) }.getOrNull() }
            .distinctBy { it.udn }
            .sortedBy { it.name.lowercase() }
    }

    /** Every child of [objectId] ("0" is the root), fetched in pages. */
    fun browse(server: DlnaServer, objectId: String): List<DidlEntry> {
        val entries = mutableListOf<DidlEntry>()
        while (true) {
            val page = Upnp.browseResponse(post(server.controlUrl, browseRequest(objectId, entries.size)))
            entries += page.entries
            if (page.returned == 0 || entries.size >= page.total) return entries
        }
    }

    private fun browseRequest(objectId: String, start: Int): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:Browse xmlns:u="${Upnp.CONTENT_DIRECTORY}">
<ObjectID>${escape(objectId)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>
<StartingIndex>$start</StartingIndex><RequestedCount>$PAGE_SIZE</RequestedCount><SortCriteria></SortCriteria>
</u:Browse></s:Body></s:Envelope>"""

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun get(url: String): String = request(url) { }

    private fun post(url: String, body: String): String = request(url) { connection ->
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPACTION", "\"${Upnp.CONTENT_DIRECTORY}#Browse\"")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private fun request(url: String, setUp: (HttpURLConnection) -> Unit): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            setUp(connection)
            val code = connection.responseCode
            // SOAP faults come back as HTTP 500 with a body worth reading.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (code !in 200..299 && (text == null || !text.contains("Fault"))) throw IOException("HTTP $code from $url")
            return text.orEmpty()
        } finally {
            connection.disconnect()
        }
    }
}
