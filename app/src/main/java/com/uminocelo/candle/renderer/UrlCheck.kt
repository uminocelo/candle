package com.uminocelo.candle.renderer

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Asks for the first byte of a URL before the player opens it, so a phone app hears
 * about a dead link as a UPnP error. Returns why the URL cannot play, or null when it
 * answered, or was only slow: a slow server may still stream, so the player decides.
 */
fun checkUrl(url: String, timeoutMs: Int = 3000): String? {
    val connection = try {
        URL(url).openConnection() as HttpURLConnection
    } catch (e: IOException) {
        return e.message ?: "bad URL"
    }
    return try {
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Range", "bytes=0-0")
        val status = connection.responseCode
        if (status >= 400) "HTTP $status" else null
    } catch (e: SocketTimeoutException) {
        null
    } catch (e: IOException) {
        e.message ?: e.javaClass.simpleName
    } finally {
        connection.disconnect()
    }
}
