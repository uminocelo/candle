package com.uminocelo.candle.upnp

import org.w3c.dom.Document
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

object Xml {
    /** Parses XML from the network; malformed input throws [IOException]. */
    fun parse(xml: String): Document = try {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No DTDs or external entities from devices on the network. Android's parser
            // rejects the feature (and does not load external DTDs), so it is best-effort.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder().parse(xml.trim().byteInputStream(Charsets.UTF_8))
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("malformed XML: ${e.message}", e)
    }
}
