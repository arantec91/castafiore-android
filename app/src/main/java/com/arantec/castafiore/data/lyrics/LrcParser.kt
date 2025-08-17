package com.arantec.castafiore.data.lyrics

/**
 * Simple LRC parser that supports multiple timestamps per line.
 */
object LrcParser {
    // Support: [mm:ss], [mm:ss.SSS], [mm:ss:SS] (centiseconds), [hh:mm:ss], [hh:mm:ss.SSS], fraction with '.' or ','
    private val timeTagRegex = Regex("\\[(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,3}))?]", RegexOption.IGNORE_CASE)

    fun parse(raw: String): List<LyricsLine> {
        val lines = mutableListOf<Pair<Long, String>>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            // Remove all time tags from text
            val text = line.replace(timeTagRegex, "").trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val hours = m.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
                val min = m.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
                val sec = m.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
                val fracStr = m.groupValues.getOrNull(4)
                val ms = when {
                    fracStr.isNullOrBlank() -> 0L
                    fracStr.length >= 3 -> fracStr.take(3).toLongOrNull() ?: 0L
                    fracStr.length == 2 -> (fracStr.toLongOrNull() ?: 0L) * 10L // centiseconds
                    else -> (fracStr.toLongOrNull() ?: 0L) * 100L // deciseconds
                }
                val timeMs = (hours * 3_600_000L) + (min * 60_000L) + (sec * 1_000L) + ms
                lines.add(timeMs to text)
            }
        }
        val sorted = lines.distinct().sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        // Compute duration for each line based on next timestamp
        val result = mutableListOf<LyricsLine>()
        for (i in sorted.indices) {
            val (t, text) = sorted[i]
            val nextT = sorted.getOrNull(i + 1)?.first
            val dur = (nextT?.minus(t))?.coerceAtLeast(0) ?: 2_000L // fallback duration 2s
            result.add(LyricsLine(timeMs = t, text = text, durationMs = dur))
        }
        return result
    }
}

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val durationMs: Long
)
