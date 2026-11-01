package com.uminocelo.candle.media

enum class MediaType {
    FOLDER,
    VIDEO,
    AUDIO,
    IMAGE,

    /** Books and papers: PDFs open in Candle's reader; the others on the server's web page. */
    DOCUMENT,
    SUBTITLE,
    OTHER;

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "ts", "m2ts", "mts",
            "mpg", "mpeg", "wmv", "flv", "3gp",
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma",
        )
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
        private val DOCUMENT_EXTENSIONS = setOf("pdf", "epub", "mobi", "azw3", "cbz", "cbr", "djvu")
        private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

        /** Classifies a file by its extension, ignoring case. Never returns [FOLDER]. */
        fun fromFileName(name: String): MediaType {
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.lastIndex) return OTHER
            return when (name.substring(dot + 1).lowercase()) {
                in VIDEO_EXTENSIONS -> VIDEO
                in AUDIO_EXTENSIONS -> AUDIO
                in IMAGE_EXTENSIONS -> IMAGE
                in DOCUMENT_EXTENSIONS -> DOCUMENT
                in SUBTITLE_EXTENSIONS -> SUBTITLE
                else -> OTHER
            }
        }
    }
}
