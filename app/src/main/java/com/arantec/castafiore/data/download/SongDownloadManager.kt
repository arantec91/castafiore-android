package com.arantec.castafiore.data.download

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * Download origin enumeration
 */
enum class DownloadOrigin {
    SONG_LIST,
    ALBUM_DETAIL,
    PLAYLIST_DETAIL,
    PLAYER,
    FAVORITES,
    SEARCH
}

class SongDownloadManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SongDownloadManager? = null

        fun getInstance(context: Context): SongDownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SongDownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Notification constants
        const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "song_downloads"
        const val DOWNLOAD_NOTIFICATION_ID = 1001

        // Work tag
        const val WORK_TAG_DOWNLOAD = "song_download"
    }

    private val workManager = WorkManager.getInstance(context)

    // StateFlow para tracking de downloads
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates

    /**
     * Check if a song is downloaded
     */
    fun isSongDownloaded(songId: String): Boolean {
        val downloadPath = createDownloadPath(songId)
        return downloadPath?.let { File(it).exists() } ?: false
    }

    /**
     * Fast check if song is downloaded (optimized version)
     */
    fun isSongDownloadedFast(songId: String): Boolean {
        return isSongDownloaded(songId)
    }

    /**
     * Check if a song is currently downloading
     */
    fun isSongDownloading(songId: String): Boolean {
        val currentState = _downloadStates.value[songId]
        return currentState?.status == DownloadStatus.DOWNLOADING
    }

    /**
     * Get download state for a song
     */
    fun getDownloadState(songId: String): DownloadState? {
        return _downloadStates.value[songId]
    }

    /**
     * Create download path for a song
     */
    fun createDownloadPath(songId: String): String? {
        val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        return File(downloadsDir, "$songId.mp3").absolutePath
    }

    /**
     * Get downloaded content URI
     */
    fun getDownloadedContentUri(songId: String): String? {
        val path = createDownloadPath(songId)
        return if (path != null && File(path).exists()) {
            "file://$path"
        } else null
    }

    /**
     * Delete a downloaded song
     */
    fun deleteSong(songId: String): Boolean {
        val downloadPath = createDownloadPath(songId)
        return downloadPath?.let {
            val file = File(it)
            if (file.exists()) {
                file.delete()
            } else false
        } ?: false
    }

    /**
     * Delete multiple songs
     */
    fun deleteMultipleSongs(songIds: List<String>): Int {
        var deletedCount = 0
        songIds.forEach { songId ->
            if (deleteSong(songId)) {
                deletedCount++
            }
        }
        return deletedCount
    }

    /**
     * Get all downloaded songs
     */
    fun getAllDownloadedSongs(): List<String> {
        val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadsDir.exists()) return emptyList()

        return downloadsDir.listFiles()?.filter { it.isFile && it.extension == "mp3" }
            ?.map { it.nameWithoutExtension } ?: emptyList()
    }

    /**
     * Get file size of a downloaded song
     */
    fun getSongFileSize(songId: String): Long {
        val downloadPath = createDownloadPath(songId)
        return downloadPath?.let {
            val file = File(it)
            if (file.exists()) file.length() else 0L
        } ?: 0L
    }

    /**
     * Create album cover path for local storage
     */
    fun createAlbumCoverPath(artist: String, album: String): String {
        val coversDir = File(context.getExternalFilesDir(null), "covers")
        if (!coversDir.exists()) {
            coversDir.mkdirs()
        }
        // Use a safe filename based on artist and album
        val safeName = "${artist}_${album}".replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(coversDir, "$safeName.jpg").absolutePath
    }

    /**
     * Create cover path for a song based on its metadata
     */
    fun createCoverPath(song: com.arantec.castafiore.data.models.Song): String {
        return createAlbumCoverPath(song.artist, song.album)
    }

    /**
     * Download songs sequentially
     */
    fun downloadSongsSequentially(
        songs: List<com.arantec.castafiore.data.models.Song>,
        origin: DownloadOrigin
    ) {
        songs.forEach { song ->
            if (!isSongDownloaded(song.id)) {
                downloadSong(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    track = song.track,
                    durationSec = song.duration,
                    suffix = song.suffix,
                    albumId = song.albumId,
                    coverArtId = song.coverArt
                )
            }
        }
    }

    fun downloadSong(
        songId: String,
        title: String,
        artist: String,
        album: String,
        track: Int? = null,
        durationSec: Int = 0,
        suffix: String? = null,
        albumId: String? = null,
        coverArtId: String? = null
    ): String {
        // Update state to downloading
        updateDownloadState(songId, DownloadState(songId, DownloadStatus.DOWNLOADING, 0))

        val inputData = Data.Builder()
            .putString(SongDownloadWorker.KEY_SONG_ID, songId)
            .putString(SongDownloadWorker.KEY_SONG_TITLE, title)
            .putString(SongDownloadWorker.KEY_SONG_ARTIST, artist)
            .putString(SongDownloadWorker.KEY_SONG_ALBUM, album)
            .putInt(SongDownloadWorker.KEY_SONG_DURATION_SEC, durationSec)
            .apply {
                track?.let { putInt(SongDownloadWorker.KEY_SONG_TRACK, it) }
                suffix?.let { putString(SongDownloadWorker.KEY_SONG_SUFFIX, it) }
                albumId?.let { putString(SongDownloadWorker.KEY_ALBUM_ID, it) }
                coverArtId?.let { putString(SongDownloadWorker.KEY_COVER_ART_ID, it) }
            }
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<SongDownloadWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(WORK_TAG_DOWNLOAD)
            .build()

        workManager.enqueue(downloadRequest)
        return downloadRequest.id.toString()
    }

    private fun updateDownloadState(songId: String, state: DownloadState) {
        val currentStates = _downloadStates.value.toMutableMap()
        currentStates[songId] = state
        _downloadStates.value = currentStates
    }

    fun cancelDownload(workId: String) {
        workManager.cancelWorkById(java.util.UUID.fromString(workId))
    }

    fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag(WORK_TAG_DOWNLOAD)
    }

    fun getDownloadWorkInfo(workId: String) =
        workManager.getWorkInfoByIdLiveData(java.util.UUID.fromString(workId))

    fun getAllDownloadsWorkInfo() =
        workManager.getWorkInfosByTagLiveData(WORK_TAG_DOWNLOAD)
}
