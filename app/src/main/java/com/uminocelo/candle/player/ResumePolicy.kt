package com.uminocelo.candle.player

import com.uminocelo.candle.resume.ResumeEntry

object ResumePolicy {
    /** Watched this much or more counts as finished, and the position is cleared. */
    const val FINISHED_FRACTION = 0.95

    /** Positions earlier than this are not worth resuming. */
    const val MIN_RESUME_MS = 10_000L

    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

    /** The entry to store after playing [target] up to [positionMs], or null to clear it. Casts are never kept. */
    fun entryFor(target: PlaybackTarget, positionMs: Long, durationMs: Long, now: Long): ResumeEntry? {
        if (target.isCast || positionMs < MIN_RESUME_MS || isFinished(positionMs, durationMs)) return null
        return ResumeEntry(target.sourceId, target.path, target.title, positionMs, durationMs.coerceAtLeast(0), now)
    }

    /** Whether reopening a file should offer to continue from [entry]. */
    fun shouldOffer(entry: ResumeEntry?): Boolean =
        entry != null && entry.positionMs >= MIN_RESUME_MS && !isFinished(entry.positionMs, entry.durationMs)
}
