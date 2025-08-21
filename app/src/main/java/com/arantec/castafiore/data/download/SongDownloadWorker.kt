package com.arantec.castafiore.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.*
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException

class SongDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val downloadManager = SongDownloadManager.getInstance(context)
    private val musicRepository = MusicRepository.getInstance(context)

    companion object {
        private const val CHANNEL_ID = "download_channel"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString("song_id") ?: return@withContext Result.failure()
        val songTitle = inputData.getString("song_title") ?: "Canción desconocida"
        val songArtist = inputData.getString("song_artist") ?: "Artista desconocido"
        val songAlbum = inputData.getString("song_album") ?: "Álbum desconocido"
        val songTrack = inputData.getInt("song_track", 0)
        val songDuration = inputData.getInt("song_duration", 0)

        try {
            createNotificationChannel()

            val song = Song(
                id = songId,
                title = songTitle,
                artist = songArtist,
                album = songAlbum,
                duration = songDuration,
                track = songTrack,
                albumId = inputData.getString("song_albumId")?.takeIf { it.isNotEmpty() },
                artistId = "",
                coverArt = inputData.getString("song_coverArt")?.takeIf { it.isNotEmpty() }
            )

            // Crear la ruta de descarga
            val downloadPath = downloadManager.createDownloadPath(song)
            val downloadUrl = downloadManager.getSongDownloadUrl(song)

            // Verificar si el archivo ya existe
            val file = File(downloadPath)
            if (file.exists()) {
                return@withContext Result.success(
                    Data.Builder()
                        .putString("file_path", downloadPath)
                        .build()
                )
            }

            // Realizar la descarga (sin notificación de progreso)
            val success = downloadFile(downloadUrl, downloadPath)

            if (success) {
                // Intentar descargar la portada en segundo plano (si hay info)
                try {
                    val coverId = song.coverArt ?: song.albumId
                    val server = musicRepository.serverUrl
                    if (!coverId.isNullOrEmpty() && !server.isNullOrEmpty()) {
                        val (u, t, s) = musicRepository.getAuthParams()
                        val coverUrl = "$server/rest/getCoverArt.view?id=$coverId&u=$u&t=$t&s=$s&v=1.16.1&c=Castafiore&size=500"
                        val coverPath = downloadManager.createCoverPath(song)
                        downloadImage(coverUrl, coverPath)
                    }
                } catch (_: Exception) { /* Ignorar errores de portada */ }

                // Eliminado: no mostrar notificación de descarga completada
                // showCompletedNotification(songTitle, songArtist)

                Result.success(
                    Data.Builder()
                        .putString("file_path", downloadPath)
                        .build()
                )
            } else {
                Result.failure(
                    Data.Builder()
                        .putString("error", "Error al descargar el archivo")
                        .build()
                )
            }
        } catch (e: Exception) {
            Result.failure(
                Data.Builder()
                    .putString("error", e.message ?: "Error desconocido")
                    .build()
            )
        }
    }

    private suspend fun downloadFile(
        url: String,
        destinationPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()

            val file = File(destinationPath)
            file.parentFile?.mkdirs()

            val sink = file.sink().buffer()
            val source = body.source()

            var totalBytesRead = 0L
            val bufferSize = 8192L
            var lastProgressUpdate = 0

            while (true) {
                val bytesRead = source.read(sink.buffer, bufferSize)
                if (bytesRead == -1L) break

                totalBytesRead += bytesRead
                sink.emit()

                // Actualizar progreso a WorkManager (sin notificación de sistema)
                if (contentLength > 0) {
                    val progress = ((totalBytesRead * 100) / contentLength).toInt()

                    if (progress >= lastProgressUpdate + 5) {
                        lastProgressUpdate = progress

                        setProgress(
                            Data.Builder()
                                .putInt("progress", progress)
                                .putLong("downloaded_bytes", totalBytesRead)
                                .putLong("total_bytes", contentLength)
                                .build()
                        )
                    }
                }
            }

            sink.close()
            source.close()
            response.close()

            return@withContext true
        } catch (_: IOException) {
            // Limpiar archivo parcial si hay error
            File(destinationPath).delete()
            return@withContext false
        }
    }

    private fun downloadImage(url: String, destinationPath: String): Boolean {
        return try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val file = File(destinationPath)
                file.parentFile?.mkdirs()
                file.sink().buffer().use { sink ->
                    sink.writeAll(body.source())
                    sink.flush()
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Descargas de música",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificaciones de descarga de canciones"
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // showCompletedNotification eliminado
}
