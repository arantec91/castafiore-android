package com.arantec.castafiore.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.arantec.castafiore.R

/**
 * Manages download notifications
 * Shows persistent notification during active downloads with progress
 */
class DownloadNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val CHANNEL_NAME = "Descargas"
        private const val NOTIFICATION_ID = 1000
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    /**
     * Create notification channel for Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Muestra el progreso de las descargas de música"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show download progress notification
     * @param current Number of completed downloads
     * @param total Total number of downloads
     * @param songTitle Title of the currently downloading song
     */
    fun showDownloadProgress(current: Int, total: Int, songTitle: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Descargando música")
            .setContentText("$songTitle ($current de $total)")
            .setProgress(total, current, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show download progress with percentage for a single song
     * @param songTitle Title of the downloading song
     * @param progress Progress percentage (0-100)
     */
    fun showSongProgress(songTitle: String, progress: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Descargando")
            .setContentText("$songTitle - $progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show download complete notification
     * @param count Number of songs downloaded
     */
    fun showDownloadComplete(count: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Descarga completada")
            .setContentText("$count ${if (count == 1) "canción descargada" else "canciones descargadas"}")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Hide/dismiss the download notification
     */
    fun hideNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
