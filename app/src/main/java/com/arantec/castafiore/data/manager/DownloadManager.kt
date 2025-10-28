package com.arantec.castafiore.data.manager

import android.content.Context
import android.os.Environment
import androidx.work.*
import com.arantec.castafiore.data.local.database.AppDatabase
import com.arantec.castafiore.data.local.entity.DownloadEntity
import com.arantec.castafiore.data.local.entity.DownloadStatus
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.workers.DownloadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Singleton Download Manager with Room database persistence
 * Manages concurrent downloads with a maximum of 3 simultaneous downloads
 * Implements automatic queue processing and retry logic
 */
class DownloadManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: DownloadManager? = null
        
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        
        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val database = AppDatabase.getInstance(context)
    private val dao = database.downloadDao()
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Flow of all active downloads for UI observation
     */
    val activeDownloads: Flow<List<DownloadEntity>> = dao.observeActiveDownloads()

    /**
     * Flow of all completed downloads
     */
    val completedDownloads: Flow<List<DownloadEntity>> = dao.observeCompletedDownloads()

    /**
     * Add a song to the download queue
     */
    suspend fun downloadSong(song: Song) {
        withContext(Dispatchers.IO) {
            // Check if already downloaded or in queue
            val existing = dao.getDownload(song.id)
            if (existing != null && existing.status == DownloadStatus.COMPLETED) {
                return@withContext
            }

            val download = DownloadEntity(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                localPath = null,
                status = DownloadStatus.PENDING,
                progress = 0,
                fileSize = song.size ?: 0L,
                bytesDownloaded = 0L,
                addedDate = System.currentTimeMillis(),
                completedDate = null,
                retryCount = 0,
                errorMessage = null
            )
            
            dao.insert(download)
            processQueue()
        }
    }

    /**
     * Add multiple songs (album or playlist) to the download queue
     */
    suspend fun downloadAlbum(songs: List<Song>) {
        withContext(Dispatchers.IO) {
            val downloads = songs.map { song ->
                DownloadEntity(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    albumId = song.albumId,
                    localPath = null,
                    status = DownloadStatus.PENDING,
                    progress = 0,
                    fileSize = song.size ?: 0L,
                    bytesDownloaded = 0L,
                    addedDate = System.currentTimeMillis(),
                    completedDate = null,
                    retryCount = 0,
                    errorMessage = null
                )
            }.filter { download ->
                // Only add if not already completed
                val existing = dao.getDownload(download.songId)
                existing == null || existing.status != DownloadStatus.COMPLETED
            }
            
            if (downloads.isNotEmpty()) {
                dao.insertAll(downloads)
                processQueue()
            }
        }
    }

    /**
     * Cancel a specific download
     */
    suspend fun cancelDownload(songId: String) {
        withContext(Dispatchers.IO) {
            // Cancel WorkManager job
            workManager.cancelAllWorkByTag("download_$songId")
            
            // Update database status
            dao.getDownload(songId)?.let { download ->
                dao.update(download.copy(status = DownloadStatus.CANCELLED))
            }
            
            // Delete partial file
            deleteLocalFile(songId)
            
            // Process queue to start next download
            processQueue()
        }
    }

    /**
     * Cancel all downloads for an album
     */
    suspend fun cancelAlbumDownloads(albumId: String) {
        withContext(Dispatchers.IO) {
            val downloads = dao.observeAlbumDownloads(albumId).first()
            downloads.forEach { download ->
                if (download.status != DownloadStatus.COMPLETED) {
                    cancelDownload(download.songId)
                }
            }
        }
    }

    /**
     * Check if a song is downloaded
     */
    suspend fun isDownloaded(songId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val download = dao.getDownload(songId)
            download?.status == DownloadStatus.COMPLETED && 
                download.localPath?.let { File(it).exists() } == true
        }
    }

    /**
     * Get download state for a specific song
     */
    suspend fun getDownloadState(songId: String): DownloadEntity? {
        return withContext(Dispatchers.IO) {
            dao.getDownload(songId)
        }
    }

    /**
     * Observe downloads for a specific album
     */
    fun observeAlbumDownloads(albumId: String): Flow<List<DownloadEntity>> {
        return dao.observeAlbumDownloads(albumId)
    }

    /**
     * Get the local file path for a downloaded song
     */
    fun getDownloadedFilePath(songId: String): String? {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        return File(musicDir, "$songId.mp3").absolutePath
    }

    /**
     * Process the download queue
     * Starts downloads up to MAX_CONCURRENT_DOWNLOADS limit
     */
    private fun processQueue() {
        scope.launch {
            val downloading = dao.getDownloadingItems()
            val availableSlots = MAX_CONCURRENT_DOWNLOADS - downloading.size
            
            if (availableSlots > 0) {
                val pending = dao.getPendingDownloads(availableSlots)
                pending.forEach { download ->
                    enqueueDownloadWork(download)
                }
            }
        }
    }

    /**
     * Enqueue a download work request
     */
    private suspend fun enqueueDownloadWork(download: DownloadEntity) {
        // Update status to DOWNLOADING
        dao.update(download.copy(status = DownloadStatus.DOWNLOADING))
        
        val data = workDataOf(
            "songId" to download.songId,
            "title" to download.title,
            "artist" to download.artist,
            "album" to download.album,
            "albumId" to (download.albumId ?: ""),
            "fileSize" to download.fileSize
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .addTag("download_${download.songId}")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        workManager.enqueueUniqueWork(
            "download_${download.songId}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Delete local file for a song
     */
    private fun deleteLocalFile(songId: String) {
        val path = getDownloadedFilePath(songId)
        path?.let {
            val file = File(it)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Called by DownloadWorker to update progress
     */
    suspend fun updateDownloadProgress(songId: String, progress: Int, bytesDownloaded: Long) {
        withContext(Dispatchers.IO) {
            dao.getDownload(songId)?.let { download ->
                dao.update(download.copy(
                    progress = progress,
                    bytesDownloaded = bytesDownloaded
                ))
            }
        }
    }

    /**
     * Called by DownloadWorker on successful completion
     */
    suspend fun markDownloadCompleted(songId: String, localPath: String) {
        withContext(Dispatchers.IO) {
            dao.getDownload(songId)?.let { download ->
                dao.update(download.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    localPath = localPath,
                    completedDate = System.currentTimeMillis()
                ))
            }
            // Process queue for next download
            processQueue()
        }
    }

    /**
     * Called by DownloadWorker on failure
     */
    suspend fun markDownloadFailed(songId: String, errorMessage: String, retryCount: Int) {
        withContext(Dispatchers.IO) {
            dao.getDownload(songId)?.let { download ->
                if (retryCount >= 3) {
                    // Max retries reached, mark as failed
                    dao.update(download.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = errorMessage,
                        retryCount = retryCount
                    ))
                    // Process queue for next download
                    processQueue()
                } else {
                    // Update retry count, keep as PENDING for retry
                    dao.update(download.copy(
                        status = DownloadStatus.PENDING,
                        retryCount = retryCount,
                        errorMessage = errorMessage
                    ))
                }
            }
        }
    }

    /**
     * Clean up completed downloads from database (keep files)
     */
    suspend fun cleanupCompletedDownloads() {
        withContext(Dispatchers.IO) {
            dao.deleteAllCompleted()
        }
    }

    /**
     * Clean up failed and cancelled downloads
     */
    suspend fun cleanupFailedDownloads() {
        withContext(Dispatchers.IO) {
            dao.deleteFailedAndCancelled()
        }
    }

    /**
     * Get all downloaded song IDs
     */
    suspend fun getAllDownloadedSongs(): List<String> {
        return withContext(Dispatchers.IO) {
            val completed = dao.observeCompletedDownloads().first()
            completed.filter { download ->
                download.localPath?.let { File(it).exists() } == true
            }.map { it.songId }
        }
    }
}
