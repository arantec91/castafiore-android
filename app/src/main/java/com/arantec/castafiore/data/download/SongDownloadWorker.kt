package com.arantec.castafiore.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.arantec.castafiore.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class SongDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        // Input keys
        const val KEY_SONG_ID = "song_id"
        const val KEY_SONG_TITLE = "song_title"
        const val KEY_SONG_ARTIST = "song_artist"
        const val KEY_SONG_ALBUM = "song_album"
        const val KEY_SONG_TRACK = "song_track"
        const val KEY_SONG_DURATION_SEC = "song_duration_sec"
        const val KEY_SONG_SUFFIX = "song_suffix"
        const val KEY_ALBUM_ID = "album_id"
        const val KEY_COVER_ART_ID = "cover_art_id"

        // Progress keys
        const val PROG_DOWNLOADED = "downloaded_bytes"
        const val PROG_TOTAL = "total_bytes"

        // Output keys
        const val OUT_CONTENT_URI = "content_uri"
        const val OUT_DISPLAY_NAME = "display_name"
        const val OUT_SIZE = "size_bytes"
    }

    private val repo by lazy { MusicRepository.getInstance(applicationContext) }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(KEY_SONG_ID) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_SONG_TITLE) ?: "Unknown"
        val artist = inputData.getString(KEY_SONG_ARTIST) ?: "Unknown"
        val album = inputData.getString(KEY_SONG_ALBUM) ?: "Unknown"
        val track = inputData.getInt(KEY_SONG_TRACK, 0).takeIf { it > 0 }
        val durationSec = inputData.getInt(KEY_SONG_DURATION_SEC, 0)
        val suffixFromApi = inputData.getString(KEY_SONG_SUFFIX)
        val albumId = inputData.getString(KEY_ALBUM_ID)
        val coverArtId = inputData.getString(KEY_COVER_ART_ID)

        try {
            val serverUrl = repo.serverUrl ?: return@withContext Result.failure()
            val (username, token, salt) = repo.getAuthParams()

            val highQuality = repo.highQualityEnabled
            val ext = if (highQuality) {
                // Keep original if possible; fallback to mp3 if unknown
                if (!suffixFromApi.isNullOrBlank()) sanitizeExt(suffixFromApi) else "mp3"
            } else {
                // Transcoded lightweight
                "mp3"
            }

            val urlBuilder = StringBuilder()
                .append(serverUrl.trimEnd('/'))
                .append("/rest/download.view?id=")
                .append(songId)
                .append("&u=")
                .append(username)
                .append("&t=")
                .append(token)
                .append("&s=")
                .append(salt)
                .append("&v=1.16.1&c=Castafiore")
            if (!highQuality) {
                urlBuilder.append("&maxBitRate=128&format=mp3")
            }
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .get()
                .build()

            val displayName = buildFileName(track, title, ext)
            val relativePath = "Music/Navidrome/${sanitize(artist)}/${sanitize(album)}"
            val mimeType = when (ext.lowercase()) {
                "flac" -> "audio/flac"
                "m4a", "mp4", "aac" -> "audio/mp4"
                "ogg", "oga" -> "audio/ogg"
                "wav" -> "audio/wav"
                else -> "audio/mpeg"
            }

            // Prepare MediaStore entry (IS_PENDING = 1)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                track?.let { put(MediaStore.Audio.Media.TRACK, it) }
                if (durationSec > 0) {
                    put(MediaStore.Audio.Media.DURATION, durationSec * 1000L)
                }
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            }

            val resolver = applicationContext.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri: Uri = resolver.insert(collection, values)
                ?: return@withContext Result.retry()

            setForegroundAsync(createForegroundInfo(title, artist, 0, 0))

            var success = false
            var totalBytes: Long = -1
            var written: Long = 0
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${'$'}{response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty body")
                    totalBytes = body.contentLength()

                    resolver.openOutputStream(itemUri, "w").use { outStream ->
                        if (outStream == null) throw IOException("Cannot open output stream")
                        streamTo(outStream, body.byteStream(), totalBytes) { bytesSoFar ->
                            written = bytesSoFar
                            setProgressAsync(Data.Builder()
                                .putLong(PROG_DOWNLOADED, bytesSoFar)
                                .putLong(PROG_TOTAL, totalBytes)
                                .build())
                            // Update notif progress if we know total
                            val progress = if (totalBytes > 0) ((bytesSoFar * 100) / totalBytes).toInt() else 0
                            setForegroundAsync(createForegroundInfo(title, artist, progress, totalBytes))
                        }
                    }
                    success = true
                }
            } catch (_: Exception) {
                // Cleanup incomplete file
                resolver.delete(itemUri, null, null)
                return@withContext Result.retry()
            } finally {
                // Mark as not pending only on success
                if (success) {
                    val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                    resolver.update(itemUri, done, null, null)
                }
            }

            if (!success) return@withContext Result.retry()

            // After audio success, attempt to download album art (best-effort)
            try {
                val coverId = coverArtId ?: albumId
                if (!coverId.isNullOrBlank()) {
                    val coverUrl = StringBuilder()
                        .append(serverUrl.trimEnd('/'))
                        .append("/rest/getCoverArt.view?id=")
                        .append(coverId)
                        .append("&u=").append(username)
                        .append("&t=").append(token)
                        .append("&s=").append(salt)
                        .append("&v=1.16.1&c=Castafiore&size=500")
                        .toString()

                    val coverReq = Request.Builder().url(coverUrl).get().build()
                    httpClient.newCall(coverReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.isNotEmpty()) {
                                val dm = SongDownloadManager.getInstance(applicationContext)
                                val path = dm.createAlbumCoverPath(artist, album)
                                // Ensure parent dirs exist (createAlbumCoverPath does mkdirs already)
                                try {
                                    File(path).outputStream().use { it.write(bytes) }
                                } catch (_: Exception) { /* ignore write errors */ }
                            }
                        }
                    }
                }
            } catch (_: Exception) { /* ignore cover errors */ }

            val size = if (written > 0) written else null
            val out = Data.Builder()
                .putString(OUT_CONTENT_URI, itemUri.toString())
                .putString(OUT_DISPLAY_NAME, displayName)
            if (size != null) out.putLong(OUT_SIZE, size)

            Result.success(out.build())
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun streamTo(
        out: OutputStream,
        input: java.io.InputStream,
        totalBytes: Long,
        onProgress: (bytesSoFar: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead: Int
        var bytesSoFar = 0L
        while (true) {
            bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            out.write(buffer, 0, bytesRead)
            bytesSoFar += bytesRead
            // Throttle progress updates slightly
            if (totalBytes <= 0 || bytesSoFar % (128 * 1024) == 0L) {
                onProgress(bytesSoFar)
            }
        }
        out.flush()
        // Final update
        onProgress(bytesSoFar)
    }

    private fun buildFileName(track: Int?, title: String, ext: String): String {
        val prefix = track?.let { String.format(Locale.US, "%02d - ", it) } ?: ""
        return prefix + sanitize(title) + "." + ext.lowercase()
    }

    private fun sanitize(input: String): String {
        return input.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun sanitizeExt(ext: String): String {
        return ext.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun createForegroundInfo(
        title: String,
        artist: String,
        progress: Int,
        totalBytes: Long
    ): ForegroundInfo {
        val channelId = SongDownloadManager.DOWNLOAD_NOTIFICATION_CHANNEL_ID
        ensureChannel(channelId)
        val contentText = if (progress in 1..99) {
            "Downloading ${'$'}progress% - ${'$'}artist"
        } else {
            "Downloading - ${'$'}artist"
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(applicationContext, android.R.color.holo_blue_light))
            .setProgress(100, progress.coerceIn(0, 100), totalBytes <= 0)
            .build()
        // Pass the foreground service type for Android 14+
        return ForegroundInfo(
            SongDownloadManager.DOWNLOAD_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun ensureChannel(channelId: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(channelId)
        if (existing == null) {
            val channel = NotificationChannel(
                channelId,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Castafiore downloads"
            nm.createNotificationChannel(channel)
        }
    }
}
