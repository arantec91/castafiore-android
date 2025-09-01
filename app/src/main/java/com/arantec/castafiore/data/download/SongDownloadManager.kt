package com.arantec.castafiore.data.download

import android.content.Context
import android.os.Environment
import androidx.lifecycle.Observer
import androidx.work.*
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

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
    }

    private val musicRepository = MusicRepository.getInstance(context)
    // Mantener workManager para compatibilidad aunque no se use
    private val workManager = WorkManager.getInstance(context)

    // Estado de las descargas (siempre vacío en modo streaming)
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    // Almacenar observadores para compatibilidad (no se usarán)
    private val workObservers: MutableMap<UUID, Observer<WorkInfo>> = Collections.synchronizedMap(mutableMapOf())

    // Estado de descarga individual
    data class DownloadState(
        val songId: String,
        val song: Song,
        val status: DownloadStatus,
        val progress: Int = 0,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val filePath: String? = null,
        val error: String? = null,
        val origin: DownloadOrigin = DownloadOrigin.UNKNOWN
    )

    /**
     * Modo streaming: no iniciar descargas
     */
    fun downloadSong(song: Song, origin: DownloadOrigin = DownloadOrigin.UNKNOWN) {
        // No-op en modo streaming
    }

    /**
     * Modo streaming: no hay descargas para cancelar
     */
    fun cancelDownload(songId: String) {
        // No-op
    }

    /**
     * Modo streaming: no encolar descargas
     */
    fun downloadSongsSequentially(songs: List<Song>, origin: DownloadOrigin = DownloadOrigin.UNKNOWN) {
        // No-op
    }

    /**
     * Pausa todas las descargas (no hay descargas en modo streaming)
     */
    fun pauseAllDownloads() {
        // No-op
    }

    /**
     * Verifica si una canción está descargada (siempre false en modo streaming)
     */
    fun isSongDownloaded(songId: String): Boolean {
        return false
    }

    /**
     * Verifica si una canción está en proceso de descarga (siempre false)
     */
    fun isSongDownloading(songId: String): Boolean {
        return false
    }

    /**
     * Obtiene la ruta del archivo descargado (siempre null)
     */
    fun getDownloadedFilePath(songId: String): String? {
        return null
    }

    /**
     * Elimina una canción descargada (no hay descargas; devolver false)
     */
    fun deleteSong(songId: String): Boolean {
        return false
    }

    /**
     * Elimina múltiples canciones descargadas (no hay descargas; devolver 0)
     */
    fun deleteMultipleSongs(songIds: List<String>): Int {
        return 0
    }

    /**
     * Obtiene el tamaño del archivo de una canción descargada (0L)
     */
    fun getSongFileSize(songId: String): Long {
        return 0L
    }

    /**
     * Formatea el tamaño del archivo en bytes a un formato legible
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    /**
     * Helper para obtener una canción por ID desde el estado actual (no se usa)
     */
    private fun getSongById(songId: String): Song? {
        return _downloadStates.value[songId]?.song
    }

    /**
     * Crea la ruta de descarga para una canción (se conserva por compatibilidad con comprobaciones de ruta)
     */
    fun createDownloadPath(song: Song): String {
        val musicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Castafiore")
        val artistDir = File(musicDir, sanitizeFileName(song.artist))
        val albumDir = File(artistDir, sanitizeFileName(song.album))
        albumDir.mkdirs()
        val fileName = "${song.track?.toString()?.padStart(2, '0') ?: "00"} - ${sanitizeFileName(song.title)}.mp3"
        return File(albumDir, fileName).absolutePath
    }

    /**
     * Crea la ruta de portada para una canción (imagen de álbum)
     */
    fun createCoverPath(song: Song): String {
        val musicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Castafiore/Covers")
        val artistDir = File(musicDir, sanitizeFileName(song.artist))
        val albumDir = File(artistDir, sanitizeFileName(song.album))
        albumDir.mkdirs()
        return File(albumDir, "cover.jpg").absolutePath
    }

    /**
     * Crea la ruta de portada usando artista y álbum
     */
    fun createAlbumCoverPath(artist: String, album: String): String {
        val musicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Castafiore/Covers")
        val artistDir = File(musicDir, sanitizeFileName(artist))
        val albumDir = File(artistDir, sanitizeFileName(album))
        albumDir.mkdirs()
        return File(albumDir, "cover.jpg").absolutePath
    }

    /**
     * Obtiene la URL de descarga de la canción (conservado por compatibilidad)
     */
    fun getSongDownloadUrl(song: Song): String {
        val (username, token, salt) = musicRepository.getAuthParams()
        val base = musicRepository.serverUrl ?: ""
        return "$base/rest/download.view?id=${song.id}&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore"
    }

    // Compatibilidad: API paralela (no-op)
    fun downloadSongsParallel(songs: List<Song>, origin: DownloadOrigin = DownloadOrigin.UNKNOWN, maxConcurrent: Int = 3) {
        // No-op
    }

    // Compatibilidad: auto-star (siempre false en modo streaming)
    fun isAlbumAutoStarAuthorized(albumId: String): Boolean {
        return false
    }

    private fun updateDownloadState(songId: String, newState: DownloadState) {
        val currentStates = _downloadStates.value.toMutableMap()
        currentStates[songId] = newState
        _downloadStates.value = currentStates
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    // Cola secuencial de descargas (sin uso en modo streaming)
    private val queue: java.util.ArrayDeque<com.arantec.castafiore.data.models.Song> = java.util.ArrayDeque()
    @Volatile private var isProcessingQueue: Boolean = false
    @Volatile private var currentWorkId: java.util.UUID? = null
}
