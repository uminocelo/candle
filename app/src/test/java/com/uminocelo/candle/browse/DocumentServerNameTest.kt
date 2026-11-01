package com.uminocelo.candle.browse

import com.uminocelo.candle.dlna.DlnaMediaSource
import com.uminocelo.candle.dlna.DlnaServer
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentServerNameTest {
    private fun dlna(name: String) = DlnaMediaSource(DlnaServer("uuid:1", name, "http://192.0.2.20:8766/dlna/control"), browse = { _, _ -> emptyList() })

    @Test
    fun `a DLNA server is named`() {
        assertEquals("Living Room Phone", documentServerName(dlna("  Living Room Phone ")))
    }

    @Test
    fun `an unnamed DLNA server is not`() {
        assertNull(documentServerName(dlna(" ")))
        assertNull("the name falls back to the UDN when the server sends none", documentServerName(dlna("uuid:1")))
    }

    @Test
    fun `other sources are not named`() {
        val usb = object : MediaSource {
            override val id = "usb:3A39-15F1"
            override val displayName = "SANDISK"
            override fun list(path: String) = emptyList<MediaItem>()
            override fun open(item: MediaItem): MediaStream = throw UnsupportedOperationException()
        }
        assertNull(documentServerName(usb))
    }
}
