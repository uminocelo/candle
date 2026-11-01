package com.uminocelo.candle.settings

import java.util.Locale

/** The `/proc/meminfo` fields the device panel shows, in kB; null when missing or unreadable. */
data class MemInfo(
    val totalKb: Long?,
    val availableKb: Long?,
    val swapTotalKb: Long?,
    val swapFreeKb: Long?,
) {
    /** zram in use; the HY300's only swap is zram. */
    val swapUsedKb: Long?
        get() = if (swapTotalKb != null && swapFreeKb != null) swapTotalKb - swapFreeKb else null

    companion object {
        /** Parses lines like `MemAvailable:  412345 kB`, skipping anything malformed. */
        fun parse(text: String): MemInfo {
            val fields = text.lineSequence().mapNotNull { line ->
                val parts = line.split(':', limit = 2)
                val value = parts.getOrNull(1)?.trim()?.substringBefore(' ')?.toLongOrNull()
                if (value == null || value < 0) null else parts[0].trim() to value
            }.toMap()
            return MemInfo(fields["MemTotal"], fields["MemAvailable"], fields["SwapTotal"], fields["SwapFree"])
        }
    }
}

/** "412 MB", or "2.9 GB" from 1 GB up (binary units, as `/proc/meminfo` and `df` use). */
fun formatKb(kb: Long): String =
    if (kb < 1024 * 1024) "${kb / 1024} MB" else String.format(Locale.US, "%.1f GB", kb / (1024.0 * 1024.0))
