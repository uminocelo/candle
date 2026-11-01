package com.uminocelo.candle.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A temporary storage root with [files] (paths with "/") created, folders included. */
internal fun storage(vararg files: String): File = Files.createTempDirectory("storage").toFile().apply {
    deleteOnExit()
    files.forEach { path ->
        val file = File(this, path)
        file.parentFile.mkdirs()
        if (path.endsWith("/")) file.mkdirs() else file.writeText("x".repeat(path.length))
    }
}

internal fun IndexEntry.Folder.names() = children.map { it.name }

class FileTypeTest {
    @Test
    fun `classifies each kind by extension, ignoring case`() {
        assertEquals(FileType(MediaKind.VIDEO, "video/x-matroska"), FileType.of("Film.MKV"))
        assertEquals(FileType(MediaKind.AUDIO, "audio/mpeg"), FileType.of("song.mp3"))
        assertEquals(FileType(MediaKind.IMAGE, "image/jpeg"), FileType.of("IMG_0001.jpeg"))
        assertEquals(FileType(MediaKind.DOCUMENT, "application/pdf"), FileType.of("tax.2026.pdf"))
        assertEquals(MediaKind.DOCUMENT, FileType.of("book.epub")?.kind)
        assertEquals(MediaKind.DOCUMENT, FileType.of("comic.cbz")?.kind)
        assertEquals(MediaKind.DOCUMENT, FileType.of("notes.txt")?.kind)
    }

    @Test
    fun `unknown or missing extensions have no type`() {
        assertNull(FileType.of("app.apk"))
        assertNull(FileType.of("README"))
        assertNull(FileType.of("archive.mp4.part"))
        assertTrue("video/mp4" in FileType.mimes)
    }
}

class MediaIndexTest {
    private fun index(root: File, vararg shared: String, clock: () -> Long = { 0L }) =
        MediaIndex({ shared.map { SharedFolder(it, File(root, it), true) }.filter { it.exists } }, maxAgeMs = 1000, clock = clock)

    @Test
    fun `builds a tree of shared folders with folders first, by name`() {
        val root = storage("Movies/b.mkv", "Movies/A.mp4", "Movies/Series/S1/e1.mkv", "Music/song.mp3", "Other/x.mp4")
        val tree = index(root, "Movies", "Music").snapshot()
        assertEquals(listOf("Movies", "Music"), tree.root.names())
        val movies = tree["Movies"] as IndexEntry.Folder
        assertEquals(listOf("Series", "A.mp4", "b.mkv"), movies.names())
        assertEquals("0", movies.parentId)
        val episode = tree["Movies/Series/S1/e1.mkv"] as IndexEntry.Item
        assertEquals("Movies/Series/S1", episode.parentId)
        assertEquals(MediaKind.VIDEO, episode.type.kind)
        assertEquals(File(root, "Movies/Series/S1/e1.mkv"), episode.file)
        assertNull("folders that are not shared are not reachable", tree["Other/x.mp4"])
    }

    @Test
    fun `skips unknown files, hidden entries and empty subfolders, but keeps empty shared folders`() {
        val root = storage("Download/app.apk", "Download/.hidden.mp4", "Download/.thumbnails/t.jpg", "Download/empty/", "Download/junk/a.tmp", "Movies/")
        val tree = index(root, "Download", "Movies").snapshot()
        assertEquals(emptyList<String>(), (tree["Download"] as IndexEntry.Folder).names())
        assertNotNull(tree["Movies"])
        assertNull(tree["Download/empty"])
    }

    @Test
    fun `a snapshot is reused until it is old, then new and deleted files show`() {
        var now = 0L
        val root = storage("Movies/a.mp4")
        val index = index(root, "Movies") { now }
        val first = index.snapshot()
        val firstUpdate = index.updateId
        File(root, "Movies/b.mp4").writeText("new")
        assertSame(first, index.snapshot())
        now = 1000
        val second = index.snapshot()
        assertNotNull(second["Movies/b.mp4"])
        assertTrue("content changed, so the update id moves", index.updateId > firstUpdate)
        File(root, "Movies/a.mp4").delete()
        now = 2000
        assertNull(index.snapshot()["Movies/a.mp4"])
    }

    @Test
    fun `an unchanged rescan keeps the update id`() {
        var now = 0L
        val index = index(storage("Movies/a.mp4"), "Movies") { now }
        index.snapshot()
        val id = index.updateId
        now = 5000
        index.snapshot()
        assertEquals(id, index.updateId)
    }

    @Test
    fun `changing the shared folders rebuilds at once`() {
        val root = storage("Movies/a.mp4", "Music/s.mp3")
        val shared = mutableListOf("Movies")
        val index = MediaIndex({ shared.map { SharedFolder(it, File(root, it), true) } }, clock = { 0L })
        assertEquals(listOf("Movies"), index.snapshot().root.names())
        shared += "Music"
        assertEquals(listOf("Movies", "Music"), index.snapshot().root.names())
    }
}
