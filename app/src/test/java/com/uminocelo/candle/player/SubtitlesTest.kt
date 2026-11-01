package com.uminocelo.candle.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlesTest {
    @Test
    fun `finds subtitles with and without a language tag`() {
        val found = sidecarSubtitles(
            "Movie.mkv",
            listOf("Movie.mkv", "Movie.srt", "Movie.en.srt", "Movie.pt-BR.vtt", "Movie.ASS"),
        )
        assertEquals(
            listOf(
                SidecarSubtitle("Movie.ASS", MimeTypes.TEXT_SSA, null),
                SidecarSubtitle("Movie.en.srt", MimeTypes.APPLICATION_SUBRIP, "en"),
                SidecarSubtitle("Movie.pt-BR.vtt", MimeTypes.TEXT_VTT, "pt-BR"),
                SidecarSubtitle("Movie.srt", MimeTypes.APPLICATION_SUBRIP, null),
            ),
            found,
        )
    }

    @Test
    fun `ignores other videos, other files and look-alike names`() {
        val found = sidecarSubtitles(
            "Movie.mkv",
            listOf("Movie 2.srt", "Movies.srt", "Other.srt", "Movie.txt", "Movie.nfo"),
        )
        assertEquals(emptyList<SidecarSubtitle>(), found)
    }
}
