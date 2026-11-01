package com.uminocelo.candle.media

import java.io.Closeable

/** What [MediaSource.open] hands to the player. */
sealed interface MediaStream {

    /** A URI the player opens itself: `file://`, `http(s)://` or `content://`. */
    data class Location(val uri: String) : MediaStream

    /**
     * Random-access bytes for sources the player cannot open by URI (SMB).
     * The player wraps it in a Media3 `DataSource`.
     */
    interface Reader : MediaStream, Closeable {
        /** Total length in bytes, or null when unknown. */
        val length: Long?

        /**
         * Reads up to [length] bytes starting at [position] into [buffer] at [offset].
         * Returns the number of bytes read, or -1 at the end of the stream.
         */
        fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    }
}
