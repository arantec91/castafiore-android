package com.arantec.castafiore.workers

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arantec.castafiore.data.manager.DownloadManager
import com.arantec.castafiore.data.network.SubsonicApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * Worker for downloading songs with retry logic and progress tracking
 * Implements exponential backoff: 2s, 4s, 8s
 * Updates Room database with progress and status
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"
        private const val MAX_RETRIES = 3
        private const val PROGRESS_UPDATE_INTERVAL = 2000L // 2 seconds
    }

    private val downloadManager = DownloadManager.getInstance(context)
    
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString("songId") ?: return@withContext Result.failure()
        val title = inputData.getString("title") ?: "Unknown"
        val artist = inputData.getString("artist") ?: "Unknown"
        val album = inputData.getString("album") ?: "Unknown"
        val fileSize = inputData.getLong("fileSize", 0L)

        return@withContext downloadWithRetry(songId, title, artist, album, fileSize, 0)
    }

    private suspend fun downloadWithRetry(
        songId: String,
        title: String,
        artist: String,
        album: String,
        fileSize: Long,
        retryCount: Int
    ): Result {
        try {
            // Get server configuration from SharedPreferences
            val prefs = applicationContext.getSharedPreferences("castafiore_prefs", Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", null) 
                ?: return markFailedAndReturn(songId, "Server URL not configured", retryCount)
            val username = prefs.getString("username", null) 
                ?: return markFailedAndReturn(songId, "Username not configured", retryCount)
            val password = prefs.getString("password", null) 
                ?: return markFailedAndReturn(songId, "Password not configured", retryCount)

            // Generate auth params for Subsonic API
            val salt = java.util.UUID.randomUUID().toString().substring(0, 8)
            val token = md5("$password$salt")

            // Build download URL
            val downloadUrl = buildDownloadUrl(serverUrl, songId, username, token, salt)

            // Create download directory
            val downloadDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: applicationContext.filesDir
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }

            // Create download file
            val downloadFile = File(downloadDir, "$songId.mp3")

            // Make HTTP request
            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code()}: ${response.message}")
            }

            val responseBody = response.body 
                ?: throw IOException("Empty response body")

            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: fileSize

            // Download file with progress tracking
            downloadFile.outputStream().use { output ->
                responseBody.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    var lastProgressUpdate = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // Check if work is stopped
                        if (isStopped) {
                            downloadFile.delete()
                            return markFailedAndReturn(songId, "Download cancelled", retryCount)
                        }

                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        // Update progress (throttled to every 2 seconds)
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL || totalBytes >= contentLength) {
                            val progress = if (contentLength > 0) {
                                ((totalBytes * 100) / contentLength).toInt()
                            } else {
                                0
                            }
                            
                            downloadManager.updateDownloadProgress(songId, progress, totalBytes)
                            
                            // Update WorkManager progress
                            setProgress(workDataOf(
                                "songId" to songId,
                                "progress" to progress,
                                "bytesDownloaded" to totalBytes,
                                "totalBytes" to contentLength
                            ))
                            
                            lastProgressUpdate = now
                        }
                    }
                }
            }

            // Mark as completed
            downloadManager.markDownloadCompleted(songId, downloadFile.absolutePath)
            
            Log.d(TAG, "Download completed: $title by $artist")
            
            return Result.success(workDataOf(
                "songId" to songId,
                "localPath" to downloadFile.absolutePath
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $songId (attempt ${retryCount + 1}/$MAX_RETRIES)", e)
            
            if (retryCount < MAX_RETRIES - 1) {
                // Exponential backoff: 2s, 4s, 8s
                val delayMs = (2L shl retryCount) * 1000
                delay(delayMs)
                return downloadWithRetry(songId, title, artist, album, fileSize, retryCount + 1)
            } else {
                // Max retries reached
                return markFailedAndReturn(songId, e.message ?: "Unknown error", retryCount + 1)
            }
        }
    }

    private suspend fun markFailedAndReturn(songId: String, errorMessage: String, retryCount: Int): Result {
        downloadManager.markDownloadFailed(songId, errorMessage, retryCount)
        Log.e(TAG, "Download failed permanently: $songId - $errorMessage")
        return Result.failure(workDataOf(
            "songId" to songId,
            "errorMessage" to errorMessage
        ))
    }

    private fun buildDownloadUrl(
        serverUrl: String,
        songId: String,
        username: String,
        token: String,
        salt: String
    ): String {
        return StringBuilder()
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
            .toString()
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
