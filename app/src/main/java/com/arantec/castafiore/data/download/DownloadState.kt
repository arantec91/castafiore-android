package com.arantec.castafiore.data.download

data class DownloadState(
    val songId: String,
    val status: DownloadStatus,
    val progress: Int = 0
)
