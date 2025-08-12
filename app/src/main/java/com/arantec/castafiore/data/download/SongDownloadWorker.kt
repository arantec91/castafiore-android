package com.arantec.castafiore.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.arantec.castafiore.R
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
        private const val NOTIFICATION_ID = 1001
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
                albumId = "",
                artistId = ""
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

            // Mostrar notificación inicial
            showDownloadNotification(songTitle, songArtist, 0)

            // Realizar la descarga
            val success = downloadFile(downloadUrl, downloadPath, songTitle, songArtist)

            if (success) {
                // Notificación de descarga completada
                showCompletedNotification(songTitle, songArtist)

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
        destinationPath: String,
        songTitle: String,
        songArtist: String
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

                // Actualizar progreso
                if (contentLength > 0) {
                    val progress = ((totalBytesRead * 100) / contentLength).toInt()

                    // Solo actualizar si el progreso cambió significativamente
                    if (progress >= lastProgressUpdate + 5) {
                        lastProgressUpdate = progress

                        // Actualizar WorkManager progress
                        setProgress(
                            Data.Builder()
                                .putInt("progress", progress)
                                .putLong("downloaded_bytes", totalBytesRead)
                                .putLong("total_bytes", contentLength)
                                .build()
                        )

                        // Actualizar notificación
                        showDownloadNotification(songTitle, songArtist, progress)
                    }
                }
            }

            sink.close()
            source.close()
            response.close()

            return@withContext true
        } catch (e: IOException) {
            // Limpiar archivo parcial si hay error
            File(destinationPath).delete()
            return@withContext false
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

    private fun showDownloadNotification(songTitle: String, artist: String, progress: Int) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Descargando música")
            .setContentText("$songTitle - $artist")
            .setSmallIcon(R.drawable.ic_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletedNotification(songTitle: String, artist: String) {
        // Primero cancelar la notificación de descarga en progreso
        notificationManager.cancel(NOTIFICATION_ID)

        // Mostrar notificación de descarga completada (con ID diferente para evitar conflictos)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Descarga completada")
            .setContentText("$songTitle - $artist")
            .setSmallIcon(R.drawable.ic_download) // Usar el mismo icono de descarga
            .setAutoCancel(true) // Se elimina automáticamente cuando se toca
            .setSilent(true)
            .build()

        // Usar un ID diferente para la notificación completada
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showErrorNotification(songTitle: String, artist: String, error: String) {
        // Cancelar la notificación de descarga en progreso
        notificationManager.cancel(NOTIFICATION_ID)

        // Mostrar notificación de error
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Error en descarga")
            .setContentText("$songTitle - $artist: $error")
            .setSmallIcon(R.drawable.ic_download) // Usar el mismo icono de descarga
            .setAutoCancel(true)
            .setSilent(true)
            .build()

        // Usar un ID diferente para la notificación de error
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }
}
