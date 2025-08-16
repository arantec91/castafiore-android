package com.arantec.castafiore.data.lyrics

import android.util.LruCache
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.network.LrcLibClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LyricsProvider {
    private val cache = LruCache<String, List<LyricsLine>>(64)

    suspend fun getSyncedLyrics(song: Song): Result<List<LyricsLine>> = withContext(Dispatchers.IO) {
        cache.get(song.id)?.let { return@withContext Result.success(it) }
        try {
            // 1st attempt: with album and duration
            val r1 = LrcLibClient.service.search(
                trackName = song.title,
                artistName = song.artist,
                albumName = song.album,
                duration = song.duration
            )
            val list1 = if (r1.isSuccessful) r1.body().orEmpty() else emptyList()
            val lrc1 = list1.firstOrNull { !it.syncedLyrics.isNullOrBlank() }?.syncedLyrics
            val parsed1 = lrc1?.let { LrcParser.parse(it) }
            if (!parsed1.isNullOrEmpty()) {
                cache.put(song.id, parsed1)
                return@withContext Result.success(parsed1)
            }
            // 2nd attempt: without album
            val r2 = LrcLibClient.service.search(
                trackName = song.title,
                artistName = song.artist,
                albumName = null,
                duration = song.duration
            )
            val list2 = if (r2.isSuccessful) r2.body().orEmpty() else emptyList()
            val lrc2 = list2.firstOrNull { !it.syncedLyrics.isNullOrBlank() }?.syncedLyrics
            val parsed2 = lrc2?.let { LrcParser.parse(it) }
            if (!parsed2.isNullOrEmpty()) {
                cache.put(song.id, parsed2)
                return@withContext Result.success(parsed2)
            }
            // Fallback: plain lyrics split to lines without timing
            val plain = (list1 + list2).firstOrNull { !it.plainLyrics.isNullOrBlank() }?.plainLyrics
            if (!plain.isNullOrBlank()) {
                val lines = plain.lines().filter { it.isNotBlank() }
                val approxDuration = (song.duration * 1000L).coerceAtLeast(1_000L)
                val perLine = (approxDuration / lines.size.coerceAtLeast(1)).coerceAtLeast(1500L)
                val parsed = lines.mapIndexed { i, text ->
                    LyricsLine(timeMs = i * perLine, text = text.trim(), durationMs = perLine)
                }
                cache.put(song.id, parsed)
                return@withContext Result.success(parsed)
            }
            Result.failure(IllegalStateException("Lyrics not found"))
        } catch (e: Exception) {
            Result.failure(e)
        }
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

