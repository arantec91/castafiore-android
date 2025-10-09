package com.arantec.castafiore.data.download

enum class DownloadStatus {
    IDLE,
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED
}
