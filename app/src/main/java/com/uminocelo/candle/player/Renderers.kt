package com.uminocelo.candle.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Hardware decoders first; the FFmpeg extension fills in AC3, E-AC3 and DTS, which the
 * HY300 has no platform decoders for.
 */
@OptIn(UnstableApi::class)
fun renderersFactory(context: Context): DefaultRenderersFactory =
    HY300RenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

/**
 * The Allwinner video decoders throw from `MediaCodec.setOutputSurface`, which ExoPlayer
 * calls when the screen surface replaces its placeholder (e.g. after the resume prompt).
 * Media3's workaround re-creates the codec instead; it only lists other devices.
 */
fun needsSetOutputSurfaceWorkaround(codecName: String): Boolean =
    codecName.startsWith("OMX.allwinner.", ignoreCase = true)

@OptIn(UnstableApi::class)
private class HY300RenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        val index = out.indexOfFirst { it is MediaCodecVideoRenderer }
        if (index >= 0) {
            out[index] = SurfaceSafeVideoRenderer(
                context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
                eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            )
        }
    }
}

@OptIn(UnstableApi::class)
private class SurfaceSafeVideoRenderer(
    context: Context,
    selector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    handler: Handler,
    listener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context, selector, allowedJoiningTimeMs, enableDecoderFallback, handler, listener, maxDroppedFramesToNotify,
) {
    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean =
        needsSetOutputSurfaceWorkaround(name) || super.codecNeedsSetOutputSurfaceWorkaround(name)
}
