package com.arantec.castafiore.data.lyrics

import android.content.Context
import android.util.LruCache
import com.arantec.castafiore.data.cache.LyricsCacheStore
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.network.LrcLibClient
import com.arantec.castafiore.data.network.LrcLibItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import android.util.Log

class LyricsProvider {
    private val cache = LruCache<String, List<LyricsLine>>(64)

    // Context-aware version that also checks/persists to disk cache
    suspend fun getSyncedLyrics(context: Context, song: Song): Result<List<LyricsLine>> = withContext(Dispatchers.IO) {
        // Memory cache first
        cache.get(song.id)?.let { return@withContext Result.success(it) }
        // Disk cache next
        try {
            LyricsCacheStore.getInstance(context).get(song.id)?.let { cached ->
                cache.put(song.id, cached)
                return@withContext Result.success(cached)
            }
        } catch (_: Exception) { }
        // Fallback to network/provider
        val fetched = getSyncedLyrics(song)
        if (fetched.isSuccess) {
            try { LyricsCacheStore.getInstance(context).put(song.id, fetched.getOrNull().orEmpty()) } catch (_: Exception) { }
        }
        fetched
    }

    // Backwards-compatible version (memory cache + network only)
    suspend fun getSyncedLyrics(song: Song): Result<List<LyricsLine>> = withContext(Dispatchers.IO) {
        cache.get(song.id)?.let { return@withContext Result.success(it) }
        try {
            val tag = "LRCLIB"
            Log.d(tag, "Fetching lyrics for ${song.title} - ${song.artist} (${song.duration}s)")
            // Build tolerant search strategy
            val originalTitle = song.title
            val normalizedTitle = normalizeTitleForSearch(originalTitle)
            val titles = linkedSetOf(originalTitle, normalizedTitle)

            val albumOptions: List<String?> = listOf(song.album, null).distinct()
            val durations: List<Int?> = buildList {
                add(song.duration)
                (song.duration - 1).takeIf { it > 0 }?.let { add(it) }
                add(song.duration + 1)
                add(null)
            }

            val tried = HashSet<String>()
            var plainLyricsCandidate: String? = null

            search@ for (title in titles) {
                for (album in albumOptions) {
                    for (dur in durations) {
                        val key = listOf(title, song.artist, album ?: "", dur?.toString() ?: "").joinToString("|")
                        if (!tried.add(key)) continue

                        val response = LrcLibClient.service.search(
                            trackName = title,
                            artistName = song.artist,
                            albumName = album,
                            duration = dur
                        )
                        if (!response.isSuccessful) {
                            Log.d(tag, "Search failed code=${response.code()} for title='$title' album='${album ?: "-"}' duration='${dur ?: "-"}'")
                            continue
                        }
                        val items = response.body().orEmpty()
                        val withSynced = items.count { !it.syncedLyrics.isNullOrBlank() }
                        val withPlain = items.count { !it.plainLyrics.isNullOrBlank() }
                        Log.d(tag, "Got ${items.size} items (synced=$withSynced, plain=$withPlain) for title='$title' album='${album ?: "-"}' duration='${dur ?: "-"}'")

                        val best = pickBestSynced(items, targetDuration = song.duration)
                        if (best != null && !best.syncedLyrics.isNullOrBlank()) {
                            Log.d(tag, "Picked synced candidate duration=${best.duration} track='${best.trackName}' artist='${best.artistName}'")
                            val parsed = LrcParser.parse(best.syncedLyrics)
                            Log.d(tag, "Parsed synced lines count=${parsed.size}")
                            if (parsed.isNotEmpty()) {
                                cache.put(song.id, parsed)
                                return@withContext Result.success(parsed)
                            }
                        }
                        if (plainLyricsCandidate == null) {
                            plainLyricsCandidate = items.firstOrNull { !it.plainLyrics.isNullOrBlank() }?.plainLyrics
                            if (plainLyricsCandidate != null) {
                                Log.d(tag, "Captured plain lyrics fallback candidate")
                            }
                        }
                    }
                }
            }

            // Fallback: plain lyrics split to lines without timing
            val plain = plainLyricsCandidate
            if (!plain.isNullOrBlank()) {
                val lines = plain.lines().filter { it.isNotBlank() }
                val approxDuration = (song.duration * 1000L).coerceAtLeast(1_000L)
                val perLine = (approxDuration / lines.size.coerceAtLeast(1)).coerceAtLeast(1500L)
                val parsed = lines.mapIndexed { i, text ->
                    LyricsLine(timeMs = i * perLine, text = text.trim(), durationMs = perLine)
                }
                Log.d(tag, "Using plain fallback, lines=${parsed.size}, perLine=${perLine}ms")
                cache.put(song.id, parsed)
                return@withContext Result.success(parsed)
            }
            Log.d(tag, "Lyrics not found after all attempts")
            Result.failure(IllegalStateException("Lyrics not found"))
        } catch (e: Exception) {
            Log.e("LRCLIB", "Error fetching/parsing lyrics: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Fire-and-forget prefetch that warms memory+disk cache
    suspend fun prefetch(context: Context, song: Song?) {
        if (song == null) return
        try { getSyncedLyrics(context, song) } catch (_: Exception) { }
    }

    // Choose the best item with synced lyrics, preferring closest duration within small tolerance
    private fun pickBestSynced(items: List<LrcLibItem>, targetDuration: Int, toleranceSec: Double = 2.0): LrcLibItem? {
        val candidates = items.filter { !it.syncedLyrics.isNullOrBlank() }
        if (candidates.isEmpty()) return null
        val target = targetDuration.toDouble()
        val withDuration = candidates.filter { it.duration != null }
        val withinTol = withDuration.filter { abs((it.duration ?: target) - target) <= toleranceSec }
        val list = if (withinTol.isNotEmpty()) withinTol else withDuration
        val pool = if (list.isNotEmpty()) list else candidates
        return pool.minByOrNull { abs((it.duration ?: target) - target) }
    }

    // Strip common decorations that harm search (feat., parentheses/brackets, remix/remaster suffixes)
    private fun normalizeTitleForSearch(title: String): String {
        var t = title
        // Remove parenthetical/bracketed qualifiers
        t = t.replace("\\([^)]*\\)".toRegex(), "").replace("\\[[^]]*]".toRegex(), "")
        // Remove common suffix after a dash that indicates version
        t = t.replace(" - .*".toRegex(), "")
        // Remove featuring credits
        t = t.replace("(?i)\\s+(feat\\.|ft\\.|featuring)\\s+.*".toRegex(), "")
        return t.trim()
    }

    companion object {
        @Volatile private var instance: LyricsProvider? = null
        fun getInstance(): LyricsProvider {
            return instance ?: synchronized(this) {
                instance ?: LyricsProvider().also { instance = it }
            }
        }
    }
}
