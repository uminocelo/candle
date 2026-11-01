package com.uminocelo.candle.resume

/** Where playback of one file stopped. Written by the player. */
data class ResumeEntry(
    val sourceId: String,
    val path: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    fun isSameFile(other: ResumeEntry) = sourceId == other.sourceId && path == other.path
}

interface ResumeStore {
    /** Most recently updated first. */
    fun entries(): List<ResumeEntry>

    fun save(entry: ResumeEntry)

    fun remove(sourceId: String, path: String)
}

/** Replaces any entry for the same file, keeps the newest [limit] entries, newest first. */
fun List<ResumeEntry>.upsert(entry: ResumeEntry, limit: Int): List<ResumeEntry> =
    (filterNot { it.isSameFile(entry) } + entry)
        .sortedByDescending { it.updatedAt }
        .take(limit)

/** One-line text form for storage. Fields are separated by the ASCII unit separator. */
object ResumeCodec {
    private const val SEP = '\u001F'

    fun encode(entry: ResumeEntry): String {
        require(SEP !in entry.sourceId && SEP !in entry.path) { "separator in id or path: ${entry.path}" }
        return listOf(
            entry.sourceId,
            entry.path,
            entry.title.replace(SEP, ' '),
            entry.positionMs,
            entry.durationMs,
            entry.updatedAt,
        ).joinToString(SEP.toString())
    }

    /** Returns null for anything that is not a well-formed entry. */
    fun decode(line: String): ResumeEntry? {
        val f = line.split(SEP)
        if (f.size != 6) return null
        return ResumeEntry(
            sourceId = f[0],
            path = f[1],
            title = f[2],
            positionMs = f[3].toLongOrNull() ?: return null,
            durationMs = f[4].toLongOrNull() ?: return null,
            updatedAt = f[5].toLongOrNull() ?: return null,
        )
    }
}
