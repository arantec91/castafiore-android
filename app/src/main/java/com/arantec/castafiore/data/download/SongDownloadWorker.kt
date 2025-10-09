package com.arantec.castafiore.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.security.MessageDigest
import java.util.*
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

    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("castafiore_prefs", Context.MODE_PRIVATE)
    }

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

        try {
            // Get server configuration directly from SharedPreferences
            val serverUrl = prefs.getString("server_url", null) ?: return@withContext Result.failure()
            val username = prefs.getString("username", null) ?: return@withContext Result.failure()
            val password = prefs.getString("password", null) ?: return@withContext Result.failure()

            // Generate auth params for Subsonic API
            val salt = UUID.randomUUID().toString().substring(0, 8)
            val token = md5("$password$salt")

            val highQuality = prefs.getBoolean("high_quality_enabled", true)
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
                urlBuilder.append("&maxBitRate=128")
            }

            val downloadUrl = urlBuilder.toString()

            // Create notification for foreground service
            setForeground(createForegroundInfo("$artist - $title"))

            // Make HTTP request
            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure()
            }

            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
            val responseBody = response.body ?: return@withContext Result.failure()

            // Create content values for MediaStore
            val displayName = createDisplayName(title, artist, track, ext)

            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.DURATION, durationSec * 1000L)
                put(MediaStore.Audio.Media.MIME_TYPE, getMimeType(ext))
                put(MediaStore.Audio.Media.IS_DOWNLOAD, 1)
                track?.let { put(MediaStore.Audio.Media.TRACK, it) }
            }

            // Insert into MediaStore
            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure()

            // Write file data
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    copyWithProgress(
                        responseBody.byteStream(),
                        outputStream,
                        contentLength
                    )
                }
            } catch (_: Exception) {
                // Clean up on failure
                resolver.delete(uri, null, null)
                return@withContext Result.failure()
            }

            // Return success with URI
            val outputData = Data.Builder()
                .putString(OUT_CONTENT_URI, uri.toString())
                .putString(OUT_DISPLAY_NAME, displayName)
                .putLong(OUT_SIZE, contentLength)
                .build()

            Result.success(outputData)

        } catch (_: Exception) {
            Result.failure()
        }
    }

    private suspend fun copyWithProgress(
        inputStream: java.io.InputStream,
        outputStream: OutputStream,
        totalBytes: Long
    ) {
        val buffer = ByteArray(8192)
        var downloadedBytes = 0L
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            outputStream.write(buffer, 0, bytesRead)
            downloadedBytes += bytesRead

            // Update progress
            val progressData = Data.Builder()
                .putLong(PROG_DOWNLOADED, downloadedBytes)
                .putLong(PROG_TOTAL, totalBytes)
                .build()

            setProgress(progressData)
        }
    }

    private fun createDisplayName(title: String, artist: String, track: Int?, ext: String): String {
        val sanitizedTitle = sanitizeFileName(title)
        val sanitizedArtist = sanitizeFileName(artist)
        return if (track != null) {
            "${track.toString().padStart(2, '0')} - $sanitizedArtist - $sanitizedTitle.$ext"
        } else {
            "$sanitizedArtist - $sanitizedTitle.$ext"
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
    }

    private fun sanitizeExt(ext: String): String {
        return ext.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun getMimeType(ext: String): String {
        return when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> "audio/mpeg"
        }
    }

    private fun createForegroundInfo(title: String): ForegroundInfo {
        val channelId = SongDownloadManager.DOWNLOAD_NOTIFICATION_CHANNEL_ID
        val notificationId = SongDownloadManager.DOWNLOAD_NOTIFICATION_ID

        // Create notification channel (required for Android 8.0+)
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Downloading song")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SongDownloadManager.DOWNLOAD_NOTIFICATION_CHANNEL_ID,
            "Song Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress of song downloads"
        }

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
