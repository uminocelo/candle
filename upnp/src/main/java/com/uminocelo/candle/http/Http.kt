package com.uminocelo.candle.http

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder

/**
 * A parsed request. Header names are lower case; [path] is decoded, without the query.
 * Routes the server streams (uploads) get an empty [body] and the bytes in [stream]
 * instead: exactly [contentLength] of them, read while the client sends.
 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    /** The client's IP address, or "" when not over a socket (tests). */
    val remoteAddress: String = "",
    val stream: InputStream? = null,
    val contentLength: Long = body.size.toLong(),
) {
    /** The body as an `application/x-www-form-urlencoded` form (what `curl -d pin=1234` sends). */
    fun form(): Map<String, String> = decodeForm(body.toString(Charsets.UTF_8))

    /** A cookie value from the `Cookie` header. */
    fun cookie(name: String): String? = headers["cookie"].orEmpty().split(';')
        .map { it.trim().split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == name }
        ?.get(1)
}

/**
 * A body read from a stream while it is written, for files too big for memory.
 * [open] returns a stream at the body's first byte; exactly [length] bytes are sent.
 */
class StreamBody(val length: Long, val open: () -> InputStream)

/** A response; [stream], when set, is sent instead of [body]. */
class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
    val stream: StreamBody? = null,
) {
    fun withHeader(name: String, value: String) = HttpResponse(status, contentType, body, headers + (name to value), stream)

    /** Writes the response; [withBody] is false for `HEAD`, which gets the same headers. */
    fun writeTo(out: OutputStream, withBody: Boolean = true) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(stream?.length ?: body.size.toLong()).append("\r\n")
            append("Connection: close\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        if (withBody) {
            if (stream != null) stream.open().use { copy(it, out, stream.length) } else out.write(body)
        }
        out.flush()
    }

    companion object {
        fun json(status: Int, body: String) = HttpResponse(status, "application/json; charset=utf-8", body.toByteArray())

        fun error(status: Int, message: String) = json(status, Json.obj("error" to message))

        /**
         * A resource of [size] bytes, honouring the request's `Range`: 200 with the whole
         * thing, 206 with one range, or 416 when the range is outside it. [open] returns a
         * stream starting at the given offset.
         */
        fun ranged(
            request: HttpRequest,
            size: Long,
            contentType: String,
            headers: Map<String, String> = emptyMap(),
            open: (offset: Long) -> InputStream,
        ): HttpResponse {
            val all = headers + ("Accept-Ranges" to "bytes")
            val range = try {
                ByteRange.parse(request.headers["range"], size)
            } catch (e: HttpError) {
                return HttpResponse(416, "text/plain", ByteArray(0), all + ("Content-Range" to "bytes */$size"))
            }
            return if (range == null) {
                HttpResponse(200, contentType, ByteArray(0), all, StreamBody(size) { open(0) })
            } else {
                HttpResponse(
                    206, contentType, ByteArray(0),
                    all + ("Content-Range" to "bytes ${range.first}-${range.last}/$size"),
                    StreamBody(range.length) { open(range.first) },
                )
            }
        }

        /** [file] with range support. */
        fun file(request: HttpRequest, file: File, contentType: String, headers: Map<String, String> = emptyMap()): HttpResponse =
            ranged(request, file.length(), contentType, headers) { offset ->
                FileInputStream(file).also { it.channel.position(offset) }
            }

        /** Copies exactly [length] bytes; a source that ends early is an error, so the client sees a cut-off body. */
        private fun copy(input: InputStream, out: OutputStream, length: Long) {
            val buffer = ByteArray(COPY_BUFFER)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw IOException("source ended $left bytes early")
                out.write(buffer, 0, n)
                left -= n
            }
        }

        private const val COPY_BUFFER = 64 * 1024
    }
}

/** One inclusive byte range of a resource, from a `Range: bytes=…` header. */
data class ByteRange(val first: Long, val last: Long) {
    val length: Long get() = last - first + 1

    companion object {
        /**
         * The range [header] asks for in a resource of [size] bytes: null to send all of it
         * (no header, another unit, or several ranges, which we may ignore), or [HttpError]
         * 416 when the range is malformed or outside the resource.
         */
        fun parse(header: String?, size: Long): ByteRange? {
            val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.trim() ?: return null
            if (',' in spec) return null
            val match = Regex("""(\d*)-(\d*)""").matchEntire(spec) ?: throw unsatisfiable()
            val (from, to) = match.destructured
            val first: Long
            val last: Long
            when {
                from.isEmpty() && to.isEmpty() -> throw unsatisfiable()
                from.isEmpty() -> { // the last N bytes
                    val n = to.toLongOrNull() ?: throw unsatisfiable()
                    if (n == 0L || size == 0L) throw unsatisfiable()
                    first = (size - n).coerceAtLeast(0)
                    last = size - 1
                }
                else -> {
                    first = from.toLongOrNull() ?: throw unsatisfiable()
                    last = if (to.isEmpty()) size - 1 else (to.toLongOrNull() ?: throw unsatisfiable()).coerceAtMost(size - 1)
                    if (first >= size || to.isNotEmpty() && to.toLong() < first) throw unsatisfiable()
                }
            }
            return ByteRange(first, last)
        }

        private fun unsatisfiable() = HttpError(416, "range not satisfiable")
    }
}

/** A request the server answers with [status] instead of passing it on. */
class HttpError(val status: Int, message: String) : Exception(message)

/** At most [length] bytes of [input]: a request body, so the handler cannot read past it. */
class BoundedInputStream(private val input: InputStream, length: Long) : InputStream() {
    private var left = length

    override fun read(): Int {
        if (left <= 0) return -1
        return input.read().also { if (it >= 0) left-- }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }
}

object HttpParser {
    const val MAX_HEAD_BYTES = 8 * 1024
    const val MAX_BODY_BYTES = 16 * 1024

    /**
     * Reads one request. Throws [HttpError] for malformed, oversized or unsupported requests.
     * When [streamed] says so for the method and path, the body is left in [input] for the
     * handler to read, with no size limit.
     */
    fun read(input: InputStream, remoteAddress: String = "", streamed: (method: String, path: String) -> Boolean = { _, _ -> false }): HttpRequest {
        val lines = readHead(input).split("\r\n", "\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.") || parts[0].isEmpty()) {
            throw HttpError(400, "malformed request line")
        }
        val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "malformed header")
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw HttpError(411, "chunked bodies are not supported")
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw HttpError(400, "bad content-length") } ?: 0L
        if (length < 0) throw HttpError(400, "bad content-length")

        val target = parts[1]
        val uri = runCatching { URI(target) }.getOrElse { throw HttpError(400, "malformed path") }
        if (uri.path.isNullOrEmpty() || !uri.path.startsWith("/")) throw HttpError(400, "malformed path")
        val query = decodeForm(uri.rawQuery.orEmpty())
        if (streamed(parts[0], uri.path)) {
            return HttpRequest(parts[0], uri.path, query, headers, ByteArray(0), remoteAddress, BoundedInputStream(input, length), length)
        }
        if (length > MAX_BODY_BYTES) throw HttpError(413, "body too large")
        return HttpRequest(parts[0], uri.path, query, headers, readBody(input, length.toInt()), remoteAddress)
    }

    // InputStream.readNBytes needs API 33; the HY300 is API 30.
    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw HttpError(400, "body shorter than content-length")
            read += n
        }
        return body
    }

    /** The request line and headers, up to the blank line; the body stays in [input]. */
    private fun readHead(input: InputStream): String {
        val head = ByteArrayOutputStream()
        var ends = 0 // how much of "\r\n\r\n" (or "\n\n") was seen last
        while (true) {
            val b = input.read()
            if (b < 0) throw HttpError(400, "connection closed before the end of the headers")
            if (head.size() >= MAX_HEAD_BYTES) throw HttpError(431, "headers too large")
            head.write(b)
            ends = when {
                b == '\n'.code -> ends + 1
                b == '\r'.code -> ends
                else -> 0
            }
            if (ends == 2) return head.toString(Charsets.UTF_8.name()).trimEnd()
        }
    }
}

fun decodeForm(text: String): Map<String, String> = text.split('&')
    .filter { it.isNotEmpty() }
    .associate { pair ->
        val (name, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

private fun reason(status: Int) = when (status) {
    200 -> "OK"
    202 -> "Accepted"
    206 -> "Partial Content"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    409 -> "Conflict"
    410 -> "Gone"
    411 -> "Length Required"
    412 -> "Precondition Failed"
    413 -> "Payload Too Large"
    416 -> "Range Not Satisfiable"
    429 -> "Too Many Requests"
    431 -> "Request Header Fields Too Large"
    500 -> "Internal Server Error"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    507 -> "Insufficient Storage"
    else -> "Status"
}

/** Just enough JSON writing for the remote's small responses (org.json is not available in unit tests). */
object Json {
    /** Already-encoded JSON (a nested object or an array) to put in an [obj] as is. */
    class Raw(val text: String)

    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (name, value) -> "${string(name)}:${value(value)}" }

    fun array(items: List<String>): Raw = Raw(items.joinToString(",", "[", "]"))

    private fun value(value: Any?): String = when (value) {
        null -> "null"
        is Raw -> value.text
        is Boolean, is Int, is Long -> value.toString()
        else -> string(value.toString())
    }

    fun string(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
