package com.uminocelo.candle.server

import com.uminocelo.candle.http.HttpError
import com.uminocelo.candle.http.HttpRequest
import com.uminocelo.candle.http.HttpResponse
import com.uminocelo.candle.http.Json
import com.uminocelo.candle.http.Router
import java.io.IOException
import java.net.URLEncoder

/**
 * The server's HTTP side: the DLNA media server under `/dlna/`, and the house web page
 * with its JSON API. Who may do what comes from [auth]: the page itself, `/ping`, the
 * session and login always answer; browsing needs [Access.canBrowse], uploading
 * [Access.canUpload].
 */
class ServerApi(
    private val settings: ServerSettings,
    private val version: String,
    private val mediaServer: MediaServer,
    private val index: MediaIndex,
    private val thumbnails: Thumbnails,
    private val auth: WebAuth,
    private val uploads: Uploads,
    /** The page's own files (`index.html`, icons…), or null for a missing one. */
    private val asset: (String) -> ByteArray?,
) {
    val router = Router().apply {
        get("/ping") { _, _ -> HttpResponse.json(200, Json.obj("ok" to true, "name" to settings.name)) }
        get("/api/session") { request, _ -> session(access(request)) }
        post("/api/login") { request, _ -> login(request) }
        post("/api/logout") { _, _ -> ok().withHeader("Set-Cookie", cookie("", 0)) }
        get("/api/status") { request, _ -> browsing(request) { HttpResponse.json(200, status()) } }
        get("/api/list") { request, _ -> browsing(request) { list(request) } }
        get("/api/search") { request, _ -> browsing(request) { search(request) } }
        post("/api/upload") { request, _ -> upload(request) }
        fallback { request -> files(request) ?: page(request.path) }
    }

    fun handle(request: HttpRequest): HttpResponse = mediaServer.handle(request) ?: router.handle(request)

    private fun access(request: HttpRequest) = auth.access(request.cookie(WebAuth.COOKIE))

    private fun browsing(request: HttpRequest, block: () -> HttpResponse): HttpResponse =
        if (access(request).canBrowse) block() else HttpResponse.error(401, "login needed")

    private fun session(access: Access) = HttpResponse.json(
        200,
        Json.obj(
            "name" to settings.name, "mode" to access.mode.name.lowercase(), "passwordSet" to access.passwordSet,
            "loggedIn" to access.loggedIn, "canBrowse" to access.canBrowse, "canUpload" to access.canUpload,
        ),
    )

    private fun login(request: HttpRequest): HttpResponse =
        when (val result = auth.login(request.remoteAddress, request.form()["password"].orEmpty())) {
            is LoginResult.Success -> session(auth.access(result.token)).withHeader("Set-Cookie", cookie(result.token, WebAuth.SESSION_MS / 1000))
            is LoginResult.Wrong -> HttpResponse.json(401, Json.obj("error" to "wrong password", "triesLeft" to result.triesLeft))
            is LoginResult.LockedOut -> HttpResponse.json(429, Json.obj("error" to "too many tries", "retryAfter" to result.secondsLeft))
            LoginResult.NoPassword -> HttpResponse.error(409, "no password is set on the phone")
        }

    private fun cookie(value: String, maxAgeSeconds: Long) =
        "${WebAuth.COOKIE}=$value; Max-Age=$maxAgeSeconds; Path=/; HttpOnly; SameSite=Lax"

    private fun status(): String = Json.obj(
        "name" to settings.name,
        "version" to version,
        "folders" to Json.array(settings.allFolders().filter { it.shared }.map { Json.obj("name" to it.name, "exists" to it.exists) }),
    )

    /** A folder's children, a page at a time. */
    private fun list(request: HttpRequest): HttpResponse {
        val path = request.query["path"].orEmpty().trim('/')
        val folder = index.snapshot()[path.ifEmpty { IndexEntry.ROOT_ID }] as? IndexEntry.Folder ?: return HttpResponse.error(404, "no such folder")
        return page(request, folder.children, "path" to path, "name" to folder.name, "parent" to folder.parentId.takeIf { path.isNotEmpty() }?.let { if (it == IndexEntry.ROOT_ID) "" else it })
    }

    private fun search(request: HttpRequest): HttpResponse {
        val text = request.query["q"].orEmpty().trim()
        if (text.length < MIN_SEARCH) return HttpResponse.error(400, "type at least $MIN_SEARCH letters")
        return page(request, index.snapshot().search(text), "q" to text)
    }

    private fun page(request: HttpRequest, entries: List<IndexEntry>, vararg extra: Pair<String, Any?>): HttpResponse {
        val offset = request.query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = (request.query["limit"]?.toIntOrNull() ?: PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val items = entries.drop(offset).take(limit).map(::item)
        return HttpResponse.json(200, Json.obj("ok" to true, *extra, "total" to entries.size, "offset" to offset, "items" to Json.array(items)))
    }

    private fun item(entry: IndexEntry): String = when (entry) {
        is IndexEntry.Folder -> Json.obj("name" to entry.name, "path" to entry.id, "kind" to "folder", "count" to entry.children.size)
        is IndexEntry.Item -> Json.obj(
            "name" to entry.name, "path" to entry.id, "kind" to entry.type.kind.name.lowercase(), "mime" to entry.type.mime,
            "size" to entry.size, "modified" to entry.modified,
            "thumb" to (entry.type.kind == MediaKind.IMAGE || entry.type.kind == MediaKind.VIDEO),
        )
    }

    /** `/files/<path>` (inline, with ranges; `?download=1` saves it) and `/thumbs/<path>`. */
    private fun files(request: HttpRequest): HttpResponse? {
        val (prefix, thumb) = when {
            request.path.startsWith(FILES) -> FILES to false
            request.path.startsWith(THUMBS) -> THUMBS to true
            else -> return null
        }
        if (!access(request).canBrowse) return HttpResponse.error(401, "login needed")
        val item = index.snapshot()[request.path.removePrefix(prefix)] as? IndexEntry.Item
        if (item == null || !item.file.isFile) return HttpResponse.error(404, "not found")
        if (thumb) {
            val jpeg = thumbnails.jpeg(item) ?: return HttpResponse.error(404, "no thumbnail")
            return HttpResponse(200, "image/jpeg", jpeg.readBytes(), mapOf("Cache-Control" to "private, max-age=86400"))
        }
        val disposition = if (request.query["download"] == "1") "attachment" else "inline"
        val encoded = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
        return HttpResponse.file(request, item.file, item.type.mime, mapOf("Content-Disposition" to "$disposition; filename*=UTF-8''$encoded"))
    }

    private fun upload(request: HttpRequest): HttpResponse {
        if (!access(request).canUpload) return HttpResponse.error(401, "log in to upload")
        val body = request.stream ?: return HttpResponse.error(400, "no body")
        val name = request.query["name"].orEmpty()
        // Into the folder the phone has open; only folders the index shares, so nothing else on the phone is writable.
        val folderId = request.query["folder"].orEmpty().trim('/')
        val folder = if (folderId.isEmpty()) null else (index.snapshot()[folderId] as? IndexEntry.Folder)?.takeIf { it.dir != null }
            ?: return HttpResponse.error(404, "no such folder")
        val saved = try {
            if (folder == null) uploads.save(name, request.contentLength, body) else uploads.save(name, request.contentLength, body, folder.dir!!)
        } catch (e: HttpError) {
            return HttpResponse.error(e.status, e.message.orEmpty())
        } catch (e: IOException) {
            return HttpResponse.error(400, "upload failed: ${e.message}")
        }
        if (folder == null && Uploads.FOLDER !in settings.sharedNames) settings.share(Uploads.FOLDER)
        index.invalidate()
        val into = folder?.id ?: Uploads.FOLDER
        return HttpResponse.json(200, Json.obj("ok" to true, "name" to saved.name, "folder" to into, "path" to "$into/${saved.name}"))
    }

    /** The page and its files, open to everyone: in private mode it shows the login. */
    private fun page(path: String): HttpResponse? {
        val name = path.removePrefix("/").ifEmpty { "index.html" }
        if (name.contains("..") || name.contains('/')) return null
        val bytes = asset(name) ?: return null
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "json" -> "application/manifest+json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
        return HttpResponse(200, type, bytes, mapOf("Cache-Control" to "no-cache"))
    }

    private fun ok() = HttpResponse.json(200, Json.obj("ok" to true))

    companion object {
        const val PORT = 8766
        const val FILES = "/files/"
        const val THUMBS = "/thumbs/"
        const val PAGE_SIZE = 100
        private const val MAX_PAGE_SIZE = 500
        private const val MIN_SEARCH = 2

        fun address(ip: String) = "http://$ip:$PORT/"

        /** Requests whose body is streamed: only uploads. */
        fun streamed(method: String, path: String) = method == "POST" && path == "/api/upload"
    }
}
