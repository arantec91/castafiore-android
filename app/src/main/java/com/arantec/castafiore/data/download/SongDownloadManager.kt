package com.arantec.castafiore.data.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.Observer
import androidx.work.*
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.Locale

// Distinción del origen de descarga
enum class DownloadOrigin {
    ALBUM,
    PLAYLIST,
    UNKNOWN
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

        const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_channel"
        const val DOWNLOAD_NOTIFICATION_ID = 1001

        // Estados de descarga
        enum class DownloadStatus {
            PENDING,
            DOWNLOADING,
            COMPLETED,
            FAILED,
            CANCELLED
        }

        private const val PREFS_NAME = "downloads_prefs"
        private fun keyFor(songId: String) = "download_uri_" + songId
    }

    private val musicRepository = MusicRepository.getInstance(context)
    private val workManager = WorkManager.getInstance(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Estado de las descargas
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    // Observadores por trabajo
    private val workObservers: MutableMap<UUID, Observer<WorkInfo>> = Collections.synchronizedMap(mutableMapOf())

    // Estado de descarga individual
    data class DownloadState(
        val songId: String,
        val song: Song,
        val status: DownloadStatus,
        val progress: Int = 0,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val filePath: String? = null, // contentUri
        val error: String? = null,
        val origin: DownloadOrigin = DownloadOrigin.UNKNOWN
    )

    fun downloadSong(song: Song, origin: DownloadOrigin = DownloadOrigin.UNKNOWN) {
        if (!musicRepository.isConfigured()) return
        if (isSongDownloaded(song.id)) return

        val input = workDataOf(
            SongDownloadWorker.KEY_SONG_ID to song.id,
            SongDownloadWorker.KEY_SONG_TITLE to song.title,
            SongDownloadWorker.KEY_SONG_ARTIST to song.artist,
            SongDownloadWorker.KEY_SONG_ALBUM to song.album,
            SongDownloadWorker.KEY_SONG_TRACK to (song.track ?: 0),
            SongDownloadWorker.KEY_SONG_DURATION_SEC to song.duration,
            SongDownloadWorker.KEY_SONG_SUFFIX to song.suffix
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<SongDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tagForSong(song.id))
            .setInputData(input)
            .build()

        updateDownloadState(song.id, DownloadState(song.id, song, DownloadStatus.PENDING, origin = origin))

        val obs = Observer<WorkInfo> { info ->
            when (info.state) {
                WorkInfo.State.ENQUEUED -> updateStatus(song, DownloadStatus.PENDING)
                WorkInfo.State.RUNNING -> {
                    val downloaded = info.progress.getLong(SongDownloadWorker.PROG_DOWNLOADED, 0L)
                    val total = info.progress.getLong(SongDownloadWorker.PROG_TOTAL, 0L)
                    val prog = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    updateDownloadState(song.id, current(song.id)?.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = prog,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    ) ?: DownloadState(song.id, song, DownloadStatus.DOWNLOADING, prog, downloaded, total, origin = origin))
                }
                WorkInfo.State.SUCCEEDED -> {
                    val uri = info.outputData.getString(SongDownloadWorker.OUT_CONTENT_URI)
                    if (!uri.isNullOrEmpty()) prefs.edit().putString(keyFor(song.id), uri).apply()
                    updateDownloadState(song.id, current(song.id)?.copy(
                        status = DownloadStatus.COMPLETED,
                        progress = 100,
                        filePath = uri
                    ) ?: DownloadState(song.id, song, DownloadStatus.COMPLETED, 100, filePath = uri))
                    removeObserver(info.id)
                }
                WorkInfo.State.FAILED -> {
                    updateDownloadState(song.id, current(song.id)?.copy(status = DownloadStatus.FAILED)
                        ?: DownloadState(song.id, song, DownloadStatus.FAILED))
                    removeObserver(info.id)
                }
                WorkInfo.State.CANCELLED -> {
                    updateDownloadState(song.id, current(song.id)?.copy(status = DownloadStatus.CANCELLED)
                        ?: DownloadState(song.id, song, DownloadStatus.CANCELLED))
                    removeObserver(info.id)
                }
                else -> {}
            }
        }
        workObservers[req.id] = obs
        workManager.getWorkInfoByIdLiveData(req.id).observeForever(obs)

        workManager.enqueue(req)
    }

    fun cancelDownload(songId: String) {
        workManager.cancelAllWorkByTag(tagForSong(songId))
        updateDownloadState(songId, current(songId)?.copy(status = DownloadStatus.CANCELLED) ?: return)
    }

    fun downloadSongsSequentially(songs: List<Song>, origin: DownloadOrigin = DownloadOrigin.UNKNOWN) {
        if (songs.isEmpty()) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val requests = songs.filter { !isSongDownloaded(it.id) }.map { song ->
            OneTimeWorkRequestBuilder<SongDownloadWorker>()
                .setConstraints(constraints)
                .addTag(tagForSong(song.id))
                .setInputData(workDataOf(
                    SongDownloadWorker.KEY_SONG_ID to song.id,
                    SongDownloadWorker.KEY_SONG_TITLE to song.title,
                    SongDownloadWorker.KEY_SONG_ARTIST to song.artist,
                    SongDownloadWorker.KEY_SONG_ALBUM to song.album,
                    SongDownloadWorker.KEY_SONG_TRACK to (song.track ?: 0),
                    SongDownloadWorker.KEY_SONG_DURATION_SEC to song.duration,
                    SongDownloadWorker.KEY_SONG_SUFFIX to song.suffix
                ))
                .build().also {
                    updateDownloadState(song.id, DownloadState(song.id, song, DownloadStatus.PENDING, origin = origin))
                }
        }
        if (requests.isEmpty()) return
        workManager.beginUniqueWork("song_download_queue", ExistingWorkPolicy.APPEND_OR_REPLACE, requests.first())
            .then(requests.drop(1)).enqueue()
    }

    fun pauseAllDownloads() {
        // WorkManager no soporta pausa; cancelar trabajos activos
        workManager.cancelAllWorkByTag("SongDownloadWorker")
    }

    fun isSongDownloaded(songId: String): Boolean {
        val uriStr = prefs.getString(keyFor(songId), null) ?: return false
        return try {
            val uri = Uri.parse(uriStr)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) { false }
    }

    fun isSongDownloading(songId: String): Boolean {
        val infos = workManager.getWorkInfosByTag(tagForSong(songId)).get()
        return infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    fun getDownloadedFilePath(songId: String): String? {
        return prefs.getString(keyFor(songId), null)
    }

    fun deleteSong(songId: String): Boolean {
        val uriStr = prefs.getString(keyFor(songId), null) ?: return false
        return try {
            val deleted = context.contentResolver.delete(Uri.parse(uriStr), null, null) > 0
            if (deleted) prefs.edit().remove(keyFor(songId)).apply()
            deleted
        } catch (_: Exception) { false }
    }

    fun deleteMultipleSongs(songIds: List<String>): Int {
        var count = 0
        songIds.forEach { if (deleteSong(it)) count++ }
        return count
    }

    fun getSongFileSize(songId: String): Long {
        val uriStr = prefs.getString(keyFor(songId), null) ?: return 0L
        return try {
            context.contentResolver.query(Uri.parse(uriStr), arrayOf(MediaStore.Audio.Media.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    private fun updateDownloadState(songId: String, newState: DownloadState) {
        val currentStates = _downloadStates.value.toMutableMap()
        currentStates[songId] = newState
        _downloadStates.value = currentStates
    }

    private fun updateStatus(song: Song, status: DownloadStatus) {
        updateDownloadState(song.id, current(song.id)?.copy(status = status) ?: DownloadState(song.id, song, status))
    }

    private fun current(songId: String): DownloadState? = _downloadStates.value[songId]

    private fun removeObserver(id: UUID) {
        workObservers.remove(id)?.let { obs ->
            try { workManager.getWorkInfoByIdLiveData(id).removeObserver(obs) } catch (_: Exception) {}
        }
    }

    private fun tagForSong(songId: String) = "download_song_" + songId

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    // Compatibilidad: rutas legacy
    fun createDownloadPath(song: Song): String {
        val musicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Castafiore")
        val artistDir = File(musicDir, sanitizeFileName(song.artist))
        val albumDir = File(artistDir, sanitizeFileName(song.album))
        albumDir.mkdirs()
        val trackNo = song.track?.toString()?.padStart(2, '0') ?: "00"
        val fileName = "$trackNo - ${'$'}{sanitizeFileName(song.title)}.mp3"
        return File(albumDir, fileName).absolutePath
    }

    fun createCoverPath(song: Song): String {
        val picturesDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Castafiore/Covers")
        val artistDir = File(picturesDir, sanitizeFileName(song.artist))
        val albumDir = File(artistDir, sanitizeFileName(song.album))
        albumDir.mkdirs()
        return File(albumDir, "cover.jpg").absolutePath
    }

    fun createAlbumCoverPath(artist: String, album: String): String {
        val picturesDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Castafiore/Covers")
        val artistDir = File(picturesDir, sanitizeFileName(artist))
        val albumDir = File(artistDir, sanitizeFileName(album))
        albumDir.mkdirs()
        return File(albumDir, "cover.jpg").absolutePath
    }

    fun getSongDownloadUrl(song: Song): String {
        val (username, token, salt) = musicRepository.getAuthParams()
        val base = musicRepository.serverUrl ?: ""
        return "$base/rest/download.view?id=${'$'}{song.id}&u=${'$'}username&t=${'$'}token&s=${'$'}salt&v=1.16.1&c=Castafiore"
    }

    fun downloadSongsParallel(songs: List<Song>, origin: DownloadOrigin = DownloadOrigin.UNKNOWN, maxConcurrent: Int = 3) {
        songs.forEach { downloadSong(it, origin) }
    }

    fun isAlbumAutoStarAuthorized(albumId: String): Boolean {
        return false
    }

    // Cola legacy (sin uso en esta implementación)
    private val queue: ArrayDeque<com.arantec.castafiore.data.models.Song> = ArrayDeque()
    @Volatile private var isProcessingQueue: Boolean = false
    @Volatile private var currentWorkId: UUID? = null
}
