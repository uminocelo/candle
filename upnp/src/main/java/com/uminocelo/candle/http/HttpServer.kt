package com.uminocelo.candle.http

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Route parameters, e.g. `name` for `/api/key/{name}`. */
typealias Params = Map<String, String>

/**
 * Matches `METHOD /path/{param}` routes. A path that matches with another method is
 * 405; anything else goes to [fallback] (GET only), or is 404. `HEAD` runs the `GET`
 * route; the server then leaves out the body.
 */
class Router {
    private class Route(val method: String, val segments: List<String>, val handler: (HttpRequest, Params) -> HttpResponse)

    private val routes = mutableListOf<Route>()
    private var fallback: ((HttpRequest) -> HttpResponse?)? = null

    fun get(pattern: String, handler: (HttpRequest, Params) -> HttpResponse) = add("GET", pattern, handler)

    fun post(pattern: String, handler: (HttpRequest, Params) -> HttpResponse) = add("POST", pattern, handler)

    /** Any other method, e.g. UPnP's `SUBSCRIBE`. */
    fun route(method: String, pattern: String, handler: (HttpRequest, Params) -> HttpResponse) = add(method, pattern, handler)

    /** Answers GET requests no route matched (static files); null means 404. */
    fun fallback(handler: (HttpRequest) -> HttpResponse?) {
        fallback = handler
    }

    private fun add(method: String, pattern: String, handler: (HttpRequest, Params) -> HttpResponse) {
        routes += Route(method, segments(pattern), handler)
    }

    fun handle(request: HttpRequest): HttpResponse {
        val path = segments(request.path)
        var pathMatched = false
        for (route in routes) {
            val params = match(route.segments, path) ?: continue
            if (route.method == request.method || route.method == "GET" && request.method == "HEAD") return route.handler(request, params)
            pathMatched = true
        }
        if (pathMatched) return HttpResponse.error(405, "method not allowed")
        if (request.method == "GET" || request.method == "HEAD") fallback?.invoke(request)?.let { return it }
        return HttpResponse.error(404, "not found")
    }

    private fun match(pattern: List<String>, path: List<String>): Params? {
        if (pattern.size != path.size) return null
        val params = mutableMapOf<String, String>()
        for ((expected, actual) in pattern.zip(path)) {
            when {
                expected.startsWith("{") && expected.endsWith("}") -> params[expected.trim('{', '}')] = actual
                expected != actual -> return null
            }
        }
        return params
    }

    private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }
}

/**
 * A minimal HTTP/1.1 server: one request per connection, at most [maxConnections]
 * handled at once, threads that exit when idle. Port 0 picks a free port ([port]).
 * A media server needs more connections than a remote: each playing video holds one.
 */
class HttpServer(
    private val requestedPort: Int,
    private val maxConnections: Int = MAX_CONNECTIONS,
    /** Which requests get their body as a stream (uploads) instead of in memory. */
    private val streamed: (method: String, path: String) -> Boolean = { _, _ -> false },
    private val handler: (HttpRequest) -> HttpResponse,
) : AutoCloseable {
    private var socket: ServerSocket? = null
    private val workers = ThreadPoolExecutor(
        maxConnections, maxConnections, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(QUEUED_CONNECTIONS),
    ).apply { allowCoreThreadTimeOut(true) }

    val port: Int get() = socket?.localPort ?: requestedPort

    fun start() {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(requestedPort))
        }
        socket = server
        Thread({ acceptLoop(server) }, "candle-http").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break // closed
            }
            try {
                workers.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                client.use { runCatching { HttpResponse.error(503, "busy").writeTo(it.getOutputStream()) } }
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        it.soTimeout = READ_TIMEOUT_MS
        var head = false
        val response = try {
            val request = HttpParser.read(it.getInputStream().buffered(), it.inetAddress.hostAddress.orEmpty(), streamed)
            head = request.method == "HEAD"
            // curl and some apps wait for this before sending a large body.
            if (request.stream != null && request.headers["expect"].equals("100-continue", ignoreCase = true)) {
                it.getOutputStream().apply { write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); flush() }
            }
            handler(request)
        } catch (e: HttpError) {
            HttpResponse.error(e.status, e.message.orEmpty())
        } catch (e: IOException) {
            return@use // the phone went away or timed out
        } catch (e: RuntimeException) {
            HttpResponse.error(500, "internal error")
        }
        // A player that seeks closes the connection mid-film; that is not an error.
        runCatching { response.writeTo(it.getOutputStream().buffered(), withBody = !head) }
    }

    override fun close() {
        runCatching { socket?.close() }
        workers.shutdownNow()
    }

    companion object {
        const val MAX_CONNECTIONS = 4
        private const val QUEUED_CONNECTIONS = 16
        private const val IDLE_SECONDS = 30L
        private const val READ_TIMEOUT_MS = 5_000
    }
}
