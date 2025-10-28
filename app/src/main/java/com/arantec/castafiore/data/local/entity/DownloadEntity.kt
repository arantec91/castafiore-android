package com.arantec.castafiore.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Download status enumeration for tracking download state
 */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Room entity for tracking song downloads
 * Includes indices for efficient querying by status and albumId
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["albumId"])
    ]
)
data class DownloadEntity(
    @PrimaryKey
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String?,
    val localPath: String?,
    val status: DownloadStatus,
    val progress: Int = 0,
    val fileSize: Long = 0,
    val bytesDownloaded: Long = 0,
    val addedDate: Long = System.currentTimeMillis(),
    val completedDate: Long? = null,
    val retryCount: Int = 0,
    val errorMessage: String? = null
)
