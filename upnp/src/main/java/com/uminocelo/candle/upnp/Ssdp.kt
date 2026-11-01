package com.uminocelo.candle.upnp

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What a device announces over SSDP: its UPnP id and type, its services, and its SERVER header. */
data class SsdpDevice(val uuid: String, val deviceType: String, val serviceTypes: List<String>, val server: String)

/** SSDP messages for a device: answers to M-SEARCH and NOTIFY alive/byebye. */
object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val MAX_AGE_SECONDS = 1800

    /** Every (NT, USN) pair [device] announces. */
    fun targets(device: SsdpDevice): List<Pair<String, String>> {
        val uuid = device.uuid
        return listOf("upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice", "uuid:$uuid" to "uuid:$uuid") +
            (listOf(device.deviceType) + device.serviceTypes).map { it to "uuid:$uuid::$it" }
    }

    /** The ST of an M-SEARCH request, or null for anything else (NOTIFYs, replies, junk). */
    fun searchTarget(packet: String): String? {
        val lines = packet.split("\r\n", "\n")
        if (!lines.first().trim().equals("M-SEARCH * HTTP/1.1", ignoreCase = true)) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().uppercase() to line.substring(colon + 1).trim()
        }.toMap()
        if (headers["MAN"]?.trim('"') != "ssdp:discover") return null
        return headers["ST"]?.takeIf { it.isNotEmpty() }
    }

    /** The replies to a search for [st]: one per matching target, none when nothing matches. */
    fun responses(st: String, device: SsdpDevice, location: String): List<String> =
        targets(device)
            .filter { (nt, _) -> st == "ssdp:all" || st == nt }
            .map { (nt, usn) ->
                "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\nEXT:\r\nLOCATION: $location\r\n" +
                    "SERVER: ${device.server}\r\nST: $nt\r\nUSN: $usn\r\n\r\n"
            }

    fun notify(nt: String, usn: String, location: String, alive: Boolean, server: String): String =
        "NOTIFY * HTTP/1.1\r\nHOST: $ADDRESS:$PORT\r\n" +
            (if (alive) "CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\nLOCATION: $location\r\nSERVER: $server\r\n" else "") +
            "NT: $nt\r\nNTS: ssdp:${if (alive) "alive" else "byebye"}\r\nUSN: $usn\r\n\r\n"
}

/**
 * Answers M-SEARCHes on the SSDP group and announces [device] when it starts, every
 * few minutes, and (byebye) when it stops; [close] blocks briefly, so call it off the main thread.
 * [location] is the device description URL, or null while the projector has no network. The caller holds a Wi-Fi multicast lock:
 * without one, Android drops multicast packets before they reach the socket.
 */
class SsdpAdvertiser(
    private val device: SsdpDevice,
    private val location: () -> String?,
    /** Where send failures are reported; Android's log on the device. */
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val group = InetAddress.getByName(Ssdp.ADDRESS)
    private val socket = MulticastSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(Ssdp.PORT))
        timeToLive = 4
        @Suppress("DEPRECATION") // the interface-less overload joins on the default interface, which is Wi-Fi here
        joinGroup(group)
    }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "candle-ssdp-notify").apply { isDaemon = true } }

    fun start() {
        Thread(::receiveLoop, "candle-ssdp").apply { isDaemon = true }.start()
        // UDP may drop the first announcement; repeat it shortly after.
        timer.schedule({ announce(alive = true) }, 0, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ announce(alive = true) }, 3, NOTIFY_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun receiveLoop() {
        val buffer = ByteArray(2048)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                break // closed
            }
            val st = Ssdp.searchTarget(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            val here = location() ?: continue
            Ssdp.responses(st, device, here).forEach { send(it, packet.socketAddress) }
        }
    }

    private fun announce(alive: Boolean) {
        val here = location() ?: return
        Ssdp.targets(device).forEach { (nt, usn) -> send(Ssdp.notify(nt, usn, here, alive, device.server), InetSocketAddress(group, Ssdp.PORT)) }
    }

    private fun send(message: String, to: SocketAddress) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: IOException) {
            log("SSDP send failed: ${e.message}")
        }
    }

    override fun close() {
        timer.shutdownNow()
        announce(alive = false)
        // Closing at once (and dropping the multicast lock after) loses byebyes still queued in the Wi-Fi driver.
        Thread.sleep(BYEBYE_DRAIN_MS)
        @Suppress("DEPRECATION")
        runCatching { socket.leaveGroup(group) }
        socket.close()
    }

    private companion object {
        const val NOTIFY_INTERVAL_SECONDS = 300L
        const val BYEBYE_DRAIN_MS = 300L
    }
}
