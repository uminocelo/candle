package com.uminocelo.candle.upnp

import org.w3c.dom.Element
import java.io.IOException

/** One UPnP control request: `urn:…:service:AVTransport:1` / `Play` with its arguments. */
data class SoapCall(val serviceType: String, val action: String, val args: Map<String, String>) {
    /** A required argument; missing ones are UPnP error 402. */
    fun arg(name: String): String = args[name] ?: throw UpnpError(402, "Invalid Args: missing $name")
}

/** A UPnP error, sent back as a SOAP fault (HTTP 500). */
class UpnpError(val code: Int, val description: String) : Exception(description)

/** SOAP envelopes for UPnP control: parsing requests, writing responses and faults. */
object Soap {
    private const val ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/"

    /**
     * Parses a control request. The action and service come from the body; the
     * `SOAPACTION` header is only checked when present. Malformed bodies are error 401.
     */
    fun parse(soapAction: String?, body: String): SoapCall {
        val doc = try {
            Xml.parse(body)
        } catch (e: IOException) {
            throw UpnpError(401, "Invalid Action: ${e.message}")
        }
        val envelope = doc.documentElement
        if (envelope.localName != "Envelope" || envelope.namespaceURI != ENVELOPE) throw UpnpError(401, "Invalid Action: not a SOAP envelope")
        val soapBody = envelope.children().firstOrNull { it.localName == "Body" } ?: throw UpnpError(401, "Invalid Action: no SOAP body")
        val action = soapBody.children().firstOrNull() ?: throw UpnpError(401, "Invalid Action: empty SOAP body")
        val serviceType = action.namespaceURI.orEmpty()
        val header = soapAction?.trim()?.trim('"')
        if (header != null && header.isNotEmpty() && header != "$serviceType#${action.localName}") {
            throw UpnpError(401, "Invalid Action: SOAPACTION $header does not match the body")
        }
        val args = action.children().associate { (it.localName ?: it.tagName) to it.textContent.orEmpty() }
        return SoapCall(serviceType, action.localName, args)
    }

    fun response(call: SoapCall, out: List<Pair<String, String>> = emptyList()): String =
        envelope(
            "<u:${call.action}Response xmlns:u=\"${call.serviceType}\">" +
                out.joinToString("") { (name, value) -> "<$name>${escape(value)}</$name>" } +
                "</u:${call.action}Response>",
        )

    fun fault(error: UpnpError): String =
        envelope(
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
                "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>${error.code}</errorCode>" +
                "<errorDescription>${escape(error.description)}</errorDescription></UPnPError></detail></s:Fault>",
        )

    fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun envelope(body: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"$ENVELOPE\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>$body</s:Body></s:Envelope>"

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
}
