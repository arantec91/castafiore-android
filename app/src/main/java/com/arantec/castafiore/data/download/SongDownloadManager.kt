package com.arantec.castafiore.data.download

import android.content.Context
import android.os.Environment
import androidx.work.*
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

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
    private val workManager = WorkManager.getInstance(context)

    // Pequeño alcance para trabajos IO internos
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // Mantener registro en memoria para evitar re-intentos de auto-favorito por el mismo álbum
    private val autoStarredAlbums: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // Estado de las descargas
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    // Estado de descarga individual
    data class DownloadState(
        val songId: String,
        val song: Song,
        val status: DownloadStatus,
        val progress: Int = 0,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val filePath: String? = null,
        val error: String? = null
    )

    /**
     * Inicia la descarga de una canción
     */
    fun downloadSong(song: Song) {
        // Verificar si ya está descargada o en proceso
        if (isSongDownloaded(song.id) || isSongDownloading(song.id)) {
            return
        }

        // Crear estado inicial
        val downloadState = DownloadState(
            songId = song.id,
            song = song,
            status = DownloadStatus.PENDING
        )

        updateDownloadState(song.id, downloadState)

        // Configurar el trabajo de descarga
        val downloadRequest = OneTimeWorkRequestBuilder<SongDownloadWorker>()
            .setInputData(createInputData(song))
            .setConstraints(createDownloadConstraints())
            .addTag("download_${song.id}")
            .build()

        // Encolar el trabajo
        workManager.enqueueUniqueWork(
            "download_${song.id}",
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )

        // Observar el progreso del trabajo
        observeDownloadProgress(song.id, downloadRequest.id)
    }

    /**
     * Cancela la descarga de una canción
     */
    fun cancelDownload(songId: String) {
        workManager.cancelUniqueWork("download_$songId")

        val currentState = _downloadStates.value[songId]
        currentState?.let { state ->
            updateDownloadState(songId, state.copy(status = DownloadStatus.CANCELLED))
        }
    }

    /**
     * Pausa todas las descargas
     */
    fun pauseAllDownloads() {
        workManager.cancelAllWorkByTag("download")

        val currentStates = _downloadStates.value.toMutableMap()
        currentStates.keys.forEach { songId ->
            currentStates[songId]?.let { state ->
                if (state.status == DownloadStatus.DOWNLOADING) {
                    currentStates[songId] = state.copy(status = DownloadStatus.CANCELLED)
                }
            }
        }
        _downloadStates.value = currentStates
    }

    /**
     * Verifica si una canción está descargada
     */
    fun isSongDownloaded(songId: String): Boolean {
        val state = _downloadStates.value[songId]
        if (state?.status == DownloadStatus.COMPLETED && state.filePath != null) {
            // Verificar que el archivo aún existe
            return File(state.filePath).exists()
        }
        return false
    }

    /**
     * Verifica si una canción está en proceso de descarga
     */
    fun isSongDownloading(songId: String): Boolean {
        val state = _downloadStates.value[songId]
        return state?.status == DownloadStatus.DOWNLOADING || state?.status == DownloadStatus.PENDING
    }

    /**
     * Obtiene la ruta del archivo descargado
     */
    fun getDownloadedFilePath(songId: String): String? {
        val state = _downloadStates.value[songId]
        return if (state?.status == DownloadStatus.COMPLETED) state.filePath else null
    }

    /**
     * Elimina una canción descargada del almacenamiento local
     */
    fun deleteSong(songId: String): Boolean {
        return try {
            val song = getSongById(songId)
            if (song != null) {
                val filePath = createDownloadPath(song)
                val file = File(filePath)

                val deleted = if (file.exists()) {
                    file.delete()
                } else {
                    true // Si el archivo no existe, consideramos que ya está "eliminado"
                }

                if (deleted) {
                    // Remover del estado de descargas
                    val currentStates = _downloadStates.value.toMutableMap()
                    currentStates.remove(songId)
                    _downloadStates.value = currentStates

                    // Cancelar cualquier trabajo de descarga pendiente
                    val workName = "download_$songId"
                    workManager.cancelUniqueWork(workName)
                }

                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Elimina múltiples canciones descargadas
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
     * Obtiene el tamaño del archivo de una canción descargada
     */
    fun getSongFileSize(songId: String): Long {
        return try {
            val song = getSongById(songId)
            if (song != null) {
                val filePath = createDownloadPath(song)
                val file = File(filePath)
                if (file.exists()) {
                    file.length()
                } else {
                    0L
                }
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
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
     * Helper para obtener una canción por ID desde el estado actual
     */
    private fun getSongById(songId: String): Song? {
        return _downloadStates.value[songId]?.song
    }

    /**
     * Crea la ruta de descarga para una canción
     */
    fun createDownloadPath(song: Song): String {
        val musicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Castafiore")
        val artistDir = File(musicDir, sanitizeFileName(song.artist))
        val albumDir = File(artistDir, sanitizeFileName(song.album))

        // Crear directorios si no existen
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
     * Obtiene la URL de descarga de la canción
     */
    fun getSongDownloadUrl(song: Song): String {
        val (username, token, salt) = musicRepository.getAuthParams()
        return "${musicRepository.serverUrl}/rest/download.view?id=${song.id}&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore"
    }

    private fun createInputData(song: Song): Data {
        return Data.Builder()
            .putString("song_id", song.id)
            .putString("song_title", song.title)
            .putString("song_artist", song.artist)
            .putString("song_album", song.album)
            .putInt("song_track", song.track ?: 0)
            .putInt("song_duration", song.duration)
            .putString("song_albumId", song.albumId ?: "")
            .putString("song_coverArt", song.coverArt ?: "")
            .build()
    }

    private fun createDownloadConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .setRequiresStorageNotLow(true)
            .build()
    }

    private fun observeDownloadProgress(songId: String, workId: UUID) {
        workManager.getWorkInfoByIdLiveData(workId).observeForever { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getInt("progress", 0)
                    val downloadedBytes = workInfo.progress.getLong("downloaded_bytes", 0)
                    val totalBytes = workInfo.progress.getLong("total_bytes", 0)

                    val currentState = _downloadStates.value[songId]
                    currentState?.let { state ->
                        updateDownloadState(songId, state.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = progress,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes
                        ))
                    }
                }
                WorkInfo.State.SUCCEEDED -> {
                    val filePath = workInfo.outputData.getString("file_path")
                    val currentState = _downloadStates.value[songId]
                    currentState?.let { state ->
                        updateDownloadState(songId, state.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            filePath = filePath
                        ))
                        // Intentar marcar como favorito el álbum si todas sus canciones están descargadas
                        maybeAutoStarAlbum(state.song)
                    }
                }
                WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString("error") ?: "Error desconocido"
                    val currentState = _downloadStates.value[songId]
                    currentState?.let { state ->
                        updateDownloadState(songId, state.copy(
                            status = DownloadStatus.FAILED,
                            error = error
                        ))
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    val currentState = _downloadStates.value[songId]
                    currentState?.let { state ->
                        updateDownloadState(songId, state.copy(
                            status = DownloadStatus.CANCELLED
                        ))
                    }
                }
                else -> { /* Estados ENQUEUED y BLOCKED no requieren acción */ }
            }
        }
    }

    // Verifica si el álbum de la canción indicada tiene todas sus canciones descargadas y, de ser así, lo marca como favorito
    private fun maybeAutoStarAlbum(song: Song) {
        val albumId = song.albumId ?: return
        if (albumId.isEmpty()) return
        if (autoStarredAlbums.contains(albumId)) return

        ioScope.launch {
            try {
                // Obtener todas las canciones del álbum
                musicRepository.getAlbumSongs(albumId).onSuccess { songs ->
                    if (songs.isNullOrEmpty()) return@onSuccess
                    val allDownloaded = songs.all { s ->
                        val path = createDownloadPath(s)
                        File(path).exists()
                    }
                    if (allDownloaded) {
                        // Evitar duplicar llamadas si ya está en favoritos
                        musicRepository.isAlbumStarred(albumId).fold(
                            onSuccess = { starred ->
                                if (!starred) {
                                    musicRepository.starAlbum(albumId)
                                }
                                autoStarredAlbums.add(albumId)
                            },
                            onFailure = {
                                // Intentar igualmente, el endpoint suele ser idempotente
                                musicRepository.starAlbum(albumId)
                                autoStarredAlbums.add(albumId)
                            }
                        )
                    }
                }
            } catch (_: Exception) {
                // Silenciar errores para no interferir con el flujo de descargas
            }
        }
    }

    private fun updateDownloadState(songId: String, newState: DownloadState) {
        val currentStates = _downloadStates.value.toMutableMap()
        currentStates[songId] = newState
        _downloadStates.value = currentStates
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
