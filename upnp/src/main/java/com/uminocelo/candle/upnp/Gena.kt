package com.uminocelo.candle.upnp

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID

/** One phone app following a service's state; [seq] is the next event's `SEQ`. */
class Subscription(val sid: String, val callbacks: List<URI>, var expiresAt: Long, var seq: Long = 0) {
    /** The `SEQ` for the next event: 0 first, then 1, 2, … wrapping back to 1. */
    fun nextSeq(): Long = seq.also { seq = if (seq == Long.MAX_VALUE) 1 else seq + 1 }
}

/**
 * GENA subscriptions to one service. Phones subscribe with a callback URL and renew
 * before [Subscription.expiresAt]; expired ones are dropped and get no more events.
 */
class Subscriptions(private val clock: () -> Long) {
    private val all = LinkedHashMap<String, Subscription>()

    /** A new subscription for [timeoutSeconds] (clamped); the oldest goes when there are too many. */
    @Synchronized
    fun subscribe(callbacks: List<URI>, timeoutSeconds: Long): Subscription {
        if (all.size >= MAX) all.remove(all.keys.first())
        val subscription = Subscription("uuid:${UUID.randomUUID()}", callbacks, clock() + grant(timeoutSeconds) * 1000)
        all[subscription.sid] = subscription
        return subscription
    }

    /** Extends [sid]; null when it is unknown or already expired. */
    @Synchronized
    fun renew(sid: String, timeoutSeconds: Long): Subscription? =
        active()[sid]?.also { it.expiresAt = clock() + grant(timeoutSeconds) * 1000 }

    @Synchronized
    fun unsubscribe(sid: String): Boolean = active().containsKey(sid).also { all.remove(sid) }

    @Synchronized
    fun clear() = all.clear()

    /** The live subscriptions, dropping expired ones. */
    @Synchronized
    fun current(): List<Subscription> = active().values.toList()

    private fun active(): Map<String, Subscription> {
        val now = clock()
        all.values.removeAll { it.expiresAt <= now }
        return all
    }

    companion object {
        const val MAX = 16
        const val MIN_SECONDS = 60L
        const val MAX_SECONDS = 1800L

        fun grant(timeoutSeconds: Long) = timeoutSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
    }
}

object Gena {
    /** `<http://phone:5000/cb><http://other/cb>` → the http URLs, in order; others are skipped. */
    fun callbacks(header: String?): List<URI> =
        Regex("<([^>]*)>").findAll(header.orEmpty())
            .mapNotNull { runCatching { URI(it.groupValues[1].trim()) }.getOrNull() }
            .filter { it.scheme.equals("http", ignoreCase = true) && !it.host.isNullOrEmpty() }
            .toList()

    /** `Second-1800` → 1800; `infinite`, missing or junk → the longest grant. */
    fun timeout(header: String?): Long =
        header?.trim()?.takeIf { it.startsWith("Second-", ignoreCase = true) }
            ?.substringAfter('-')?.toLongOrNull() ?: Subscriptions.MAX_SECONDS

    /** The NOTIFY request for [body] to [callback], as written on the socket. */
    fun notifyRequest(callback: URI, sid: String, seq: Long, body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val path = (callback.rawPath?.ifEmpty { null } ?: "/") + (callback.rawQuery?.let { "?$it" } ?: "")
        val host = if (callback.port > 0) "${callback.host}:${callback.port}" else callback.host
        val head = "NOTIFY $path HTTP/1.1\r\nHOST: $host\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
            "CONTENT-LENGTH: ${bytes.size}\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: $sid\r\nSEQ: $seq\r\n" +
            "CONNECTION: close\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + bytes
    }

    /** Sends one event and reads the status line; false when the phone did not take it. */
    fun send(callback: URI, sid: String, seq: Long, body: String, timeoutMs: Int = 2000): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(callback.host, if (callback.port > 0) callback.port else 80), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write(notifyRequest(callback, sid, seq, body))
                flush()
            }
            val status = socket.getInputStream().bufferedReader().readLine().orEmpty()
            status.split(' ').getOrNull(1) == "200"
        }
    } catch (e: IOException) {
        false
    }
}
