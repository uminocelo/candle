package com.uminocelo.candle.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
private const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"

private fun envelope(service: String, action: String, args: String) =
    """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""

private fun upnpError(block: () -> Unit): Int = try {
    block()
    fail("expected a UpnpError")
    0
} catch (e: UpnpError) {
    e.code
}

class SoapTest {
    @Test
    fun `parses the action, service and arguments`() {
        val body = envelope(AVT, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>http://phone/v.mp4?a=1&amp;b=2</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        val call = Soap.parse("\"${AVT}#SetAVTransportURI\"", body)
        assertEquals(AVT, call.serviceType)
        assertEquals("SetAVTransportURI", call.action)
        assertEquals(mapOf("InstanceID" to "0", "CurrentURI" to "http://phone/v.mp4?a=1&b=2", "CurrentURIMetaData" to ""), call.args)
        assertEquals("Play", Soap.parse(null, envelope(AVT, "Play", "<InstanceID>0</InstanceID>")).action)
    }

    @Test
    fun `malformed or mismatched requests are UPnP error 401`() {
        assertEquals(401, upnpError { Soap.parse(null, "<s:Envelope") })
        assertEquals(401, upnpError { Soap.parse(null, "") })
        assertEquals(401, upnpError { Soap.parse(null, "<Envelope><Body/></Envelope>") })
        assertEquals(401, upnpError { Soap.parse(null, envelope(AVT, "Play", "").replace(Regex("<u:Play.*</u:Play>"), "")) })
        assertEquals(401, upnpError { Soap.parse("\"${AVT}#Stop\"", envelope(AVT, "Play", "")) })
    }

    @Test
    fun `a missing argument is error 402`() {
        val call = Soap.parse(null, envelope(AVT, "Play", ""))
        assertEquals(402, upnpError { call.arg("InstanceID") })
    }

    @Test
    fun `responses and faults are SOAP envelopes`() {
        val call = SoapCall(CM, "GetCurrentConnectionIDs", emptyMap())
        val response = Xml.parse(Soap.response(call, listOf("ConnectionIDs" to "0 & <1>")))
        val element = response.getElementsByTagNameNS(CM, "GetCurrentConnectionIDsResponse").item(0)
        assertEquals("0 & <1>", element.textContent)
        val fault = Xml.parse(Soap.fault(UpnpError(716, "Resource not found")))
        assertEquals("716", fault.getElementsByTagNameNS("*", "errorCode").item(0).textContent)
    }
}
