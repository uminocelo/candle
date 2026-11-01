package com.uminocelo.candle.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderersTest {
    @Test
    fun `surface workaround applies to Allwinner decoders only`() {
        assertTrue(needsSetOutputSurfaceWorkaround("OMX.allwinner.video.decoder.avc"))
        assertTrue(needsSetOutputSurfaceWorkaround("OMX.Allwinner.video.decoder.hevc"))
        assertFalse(needsSetOutputSurfaceWorkaround("c2.android.avc.decoder"))
        assertFalse(needsSetOutputSurfaceWorkaround("OMX.google.h264.decoder"))
    }
}
