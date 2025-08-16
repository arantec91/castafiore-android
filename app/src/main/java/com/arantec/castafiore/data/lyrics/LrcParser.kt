package com.arantec.castafiore.data.lyrics

/**
 * Simple LRC parser that supports multiple timestamps per line.
 */
object LrcParser {
    private val timeTagRegex = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]", RegexOption.IGNORE_CASE)

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
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = m.groupValues.getOrNull(3)
                val ms = when {
                    fracStr.isNullOrBlank() -> 0L
                    fracStr.length == 3 -> fracStr.toLongOrNull() ?: 0L
                    else -> (fracStr.toLongOrNull() ?: 0L) * 10L // e.g. 12 -> 120ms, 12 centiseconds
                }
                val timeMs = (min * 60_000L) + (sec * 1_000L) + ms
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

