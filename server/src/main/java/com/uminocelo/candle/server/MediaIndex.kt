package com.uminocelo.candle.server

import java.io.File

/** What a file is to a player or reader; files of no kind are left out of the index. */
enum class MediaKind { VIDEO, AUDIO, IMAGE, DOCUMENT }

/** A file type the server shares: its kind and MIME type. */
data class FileType(val kind: MediaKind, val mime: String) {
    companion object {
        private val byExtension: Map<String, FileType> = buildMap {
            fun add(kind: MediaKind, mime: String, vararg extensions: String) = extensions.forEach { put(it, FileType(kind, mime)) }
            add(MediaKind.VIDEO, "video/mp4", "mp4", "m4v")
            add(MediaKind.VIDEO, "video/x-matroska", "mkv")
            add(MediaKind.VIDEO, "video/webm", "webm")
            add(MediaKind.VIDEO, "video/x-msvideo", "avi")
            add(MediaKind.VIDEO, "video/quicktime", "mov")
            add(MediaKind.VIDEO, "video/mp2t", "ts", "m2ts", "mts")
            add(MediaKind.VIDEO, "video/mpeg", "mpg", "mpeg")
            add(MediaKind.VIDEO, "video/3gpp", "3gp")
            add(MediaKind.VIDEO, "video/x-ms-wmv", "wmv")
            add(MediaKind.VIDEO, "video/x-flv", "flv")
            add(MediaKind.AUDIO, "audio/mpeg", "mp3")
            add(MediaKind.AUDIO, "audio/mp4", "m4a")
            add(MediaKind.AUDIO, "audio/aac", "aac")
            add(MediaKind.AUDIO, "audio/flac", "flac")
            add(MediaKind.AUDIO, "audio/ogg", "ogg", "oga", "opus")
            add(MediaKind.AUDIO, "audio/wav", "wav")
            add(MediaKind.AUDIO, "audio/x-ms-wma", "wma")
            add(MediaKind.IMAGE, "image/jpeg", "jpg", "jpeg")
            add(MediaKind.IMAGE, "image/png", "png")
            add(MediaKind.IMAGE, "image/gif", "gif")
            add(MediaKind.IMAGE, "image/webp", "webp")
            add(MediaKind.IMAGE, "image/heic", "heic")
            add(MediaKind.IMAGE, "image/heif", "heif")
            add(MediaKind.IMAGE, "image/bmp", "bmp")
            add(MediaKind.DOCUMENT, "application/pdf", "pdf")
            add(MediaKind.DOCUMENT, "application/epub+zip", "epub")
            add(MediaKind.DOCUMENT, "application/x-mobipocket-ebook", "mobi")
            add(MediaKind.DOCUMENT, "application/vnd.comicbook+zip", "cbz")
            add(MediaKind.DOCUMENT, "application/vnd.comicbook-rar", "cbr")
            add(MediaKind.DOCUMENT, "text/plain", "txt")
            add(MediaKind.DOCUMENT, "text/markdown", "md")
            add(MediaKind.DOCUMENT, "application/rtf", "rtf")
            add(MediaKind.DOCUMENT, "application/msword", "doc")
            add(MediaKind.DOCUMENT, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
            add(MediaKind.DOCUMENT, "application/vnd.ms-excel", "xls")
            add(MediaKind.DOCUMENT, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
            add(MediaKind.DOCUMENT, "application/vnd.ms-powerpoint", "ppt")
            add(MediaKind.DOCUMENT, "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx")
            add(MediaKind.DOCUMENT, "application/vnd.oasis.opendocument.text", "odt")
        }

        /** The type of a file name by its extension, or null for one the server does not share. */
        fun of(name: String): FileType? = byExtension[name.substringAfterLast('.', "").lowercase()]

        /** Every MIME type the server shares, for ConnectionManager. */
        val mimes: List<String> get() = byExtension.values.map { it.mime }.distinct()
    }
}

/**
 * One node of the index. [id] is the path from the phone's storage root ("Movies/Film.mkv"),
 * or [ROOT_ID]: stable across rescans, so players can come back to a folder.
 */
sealed class IndexEntry {
    abstract val id: String
    abstract val parentId: String
    abstract val name: String

    /** A folder; [dir] is where it is on the phone (null for the root, which is only a list of shared folders). */
    data class Folder(
        override val id: String,
        override val parentId: String,
        override val name: String,
        val children: List<IndexEntry>,
        val dir: File? = null,
    ) : IndexEntry()

    data class Item(
        override val id: String,
        override val parentId: String,
        override val name: String,
        val file: File,
        val type: FileType,
        val size: Long,
        val modified: Long,
    ) : IndexEntry()

    companion object {
        const val ROOT_ID = "0"
    }
}

/** One scan of the shared folders: the tree and every entry by id. */
class IndexSnapshot(val root: IndexEntry.Folder) {
    private val byId: Map<String, IndexEntry> = buildMap { collect(root) }

    operator fun get(id: String): IndexEntry? = byId[id]

    /** Folders and files whose name contains [text], ignoring case, in tree order. */
    fun search(text: String): List<IndexEntry> {
        val wanted = text.trim()
        val found = mutableListOf<IndexEntry>()
        fun walk(folder: IndexEntry.Folder) {
            for (child in folder.children) {
                if (child.name.contains(wanted, ignoreCase = true)) found += child
                if (child is IndexEntry.Folder) walk(child)
            }
        }
        if (wanted.isNotEmpty()) walk(root)
        return found
    }

    /** A fingerprint of names, sizes and dates: changes whenever a player's view would. */
    val signature: Int = byId.values.sumOf { entry ->
        when (entry) {
            is IndexEntry.Folder -> entry.id.hashCode()
            is IndexEntry.Item -> 31 * (31 * entry.id.hashCode() + entry.size.hashCode()) + entry.modified.hashCode()
        }
    }

    private fun MutableMap<String, IndexEntry>.collect(entry: IndexEntry) {
        put(entry.id, entry)
        if (entry is IndexEntry.Folder) entry.children.forEach { collect(it) }
    }
}

/**
 * The shared folders as a tree of the files the server knows a type for. A snapshot is
 * reused for [maxAgeMs] and rebuilt after that, or at once when the shared folders
 * change, so new and deleted files show within a minute without watching the disk.
 * Hidden files and folders, and folders with nothing to share, are left out.
 */
class MediaIndex(
    private val folders: () -> List<SharedFolder>,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var snapshot: IndexSnapshot? = null
    private var builtAt = 0L
    private var builtFrom: List<SharedFolder> = emptyList()
    private var stale = false

    /** Bumped whenever a rebuild finds different content (ContentDirectory's SystemUpdateID). */
    var updateId = 0
        private set

    @Synchronized
    fun snapshot(): IndexSnapshot {
        val shared = folders()
        val current = snapshot
        if (current != null && !stale && shared == builtFrom && clock() - builtAt < maxAgeMs) return current
        val fresh = IndexSnapshot(IndexEntry.Folder(IndexEntry.ROOT_ID, "-1", "", shared.mapNotNull { scan(it.dir, it.name, IndexEntry.ROOT_ID, 0) }))
        if (current == null || fresh.signature != current.signature) updateId++
        snapshot = fresh
        builtAt = clock()
        builtFrom = shared
        stale = false
        return fresh
    }

    /** Rescans on the next use: after an upload, so the new file shows at once. */
    @Synchronized
    fun invalidate() {
        stale = true
    }

    private fun scan(file: File, id: String, parentId: String, depth: Int): IndexEntry? {
        if (file.isDirectory) {
            if (depth > MAX_DEPTH) return null
            val children = file.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") }
                .mapNotNull { scan(it, "$id/${it.name}", id, depth + 1) }
                .sortedWith(compareBy<IndexEntry> { it !is IndexEntry.Folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            // The top-level shared folders always show, even empty; empty subfolders are noise.
            return if (children.isEmpty() && depth > 0) null else IndexEntry.Folder(id, parentId, file.name, children, file)
        }
        val type = FileType.of(file.name) ?: return null
        return IndexEntry.Item(id, parentId, file.name, file, type, file.length(), file.lastModified())
    }

    companion object {
        const val MAX_AGE_MS = 30_000L

        /** Deeper trees are almost always app caches, not anyone's films. */
        private const val MAX_DEPTH = 12
    }
}
