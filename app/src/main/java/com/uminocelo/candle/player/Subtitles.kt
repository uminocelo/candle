package com.uminocelo.candle.player

import androidx.media3.common.MimeTypes

/** A subtitle file next to a video, e.g. `Movie.en.srt` beside `Movie.mkv`. */
data class SidecarSubtitle(val fileName: String, val mimeType: String, val language: String?)

private val SUBTITLE_MIME_TYPES = mapOf(
    "srt" to MimeTypes.APPLICATION_SUBRIP,
    "vtt" to MimeTypes.TEXT_VTT,
    "ass" to MimeTypes.TEXT_SSA,
    "ssa" to MimeTypes.TEXT_SSA,
)

/**
 * Finds subtitles for [videoFileName] among [siblingNames]: same base name, optionally
 * followed by a language tag (`.en`), then a supported extension. Sorted by file name.
 */
fun sidecarSubtitles(videoFileName: String, siblingNames: List<String>): List<SidecarSubtitle> {
    val base = videoFileName.substringBeforeLast('.', videoFileName)
    return siblingNames.mapNotNull { name ->
        val mimeType = SUBTITLE_MIME_TYPES[name.substringAfterLast('.', "").lowercase()] ?: return@mapNotNull null
        val stem = name.substringBeforeLast('.')
        when {
            stem == base -> SidecarSubtitle(name, mimeType, null)
            stem.startsWith("$base.") -> SidecarSubtitle(name, mimeType, stem.removePrefix("$base.").ifEmpty { null })
            else -> null
        }
    }.sortedBy { it.fileName }
}
