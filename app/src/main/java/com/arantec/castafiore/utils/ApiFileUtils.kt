package com.arantec.castafiore.utils

import android.content.Context
import android.text.format.Formatter
import com.arantec.castafiore.data.network.CastafioreClient
import java.io.File

/**
 * Utility functions for file and path operations
 */
object FileUtils {

    /**
     * Format file size in human readable format
     */
    fun formatFileSize(context: Context, sizeBytes: Long): String {
        return Formatter.formatFileSize(context, sizeBytes)
    }

    /**
     * Create cover art path for caching
     */
    fun createCoverPath(context: Context, coverArtId: String): String? {
        if (coverArtId.isBlank()) return null
        val coversDir = File(context.cacheDir, "covers")
        if (!coversDir.exists()) {
            coversDir.mkdirs()
        }
        return File(coversDir, "$coverArtId.jpg").absolutePath
    }

    /**
     * Create album cover path for caching
     */
    fun createAlbumCoverPath(context: Context, albumId: String): String? {
        if (albumId.isBlank()) return null
        val coversDir = File(context.cacheDir, "album_covers")
        if (!coversDir.exists()) {
            coversDir.mkdirs()
        }
        return File(coversDir, "$albumId.jpg").absolutePath
    }

    /**
     * Create download path for a song
     */
    fun createDownloadPath(context: Context, songId: String): String? {
        if (songId.isBlank()) return null
        val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        return File(downloadsDir, "$songId.mp3").absolutePath
    }

    /**
     * Get downloaded content URI
     */
    fun getDownloadedContentUri(context: Context, songId: String): String? {
        val path = createDownloadPath(context, songId)
        return if (path != null && File(path).exists()) {
            "file://$path"
        } else null
    }

    /**
     * Get file size of a downloaded song
     */
    fun getSongFileSize(context: Context, songId: String): Long {
        val downloadPath = createDownloadPath(context, songId)
        return downloadPath?.let {
            val file = File(it)
            if (file.exists()) file.length() else 0L
        } ?: 0L
    }

    /**
     * Check if file exists
     */
    fun exists(filePath: String?): Boolean {
        return filePath?.let { File(it).exists() } ?: false
    }
}

/**
 * Utility functions for API operations
 */
object ApiUtils {

    /**
     * Clean song ID by removing any suffix (e.g., "56012_1" -> "56012")
     * Some APIs return IDs with suffixes that need to be removed for certain operations
     */
    fun cleanSongId(songId: String): String {
        return songId.substringBefore('_')
    }

    /**
     * Get cover art URL for an item
     */
    fun getCoverArtUrl(
        baseUrl: String,
        coverArtId: String,
        size: Int? = null
    ): String {
        val sizeParam = size?.let { "&size=$it" } ?: ""
        return "$baseUrl/rest/getCoverArt?id=$coverArtId$sizeParam"
    }

    /**
     * Get streaming URL for a song
     */
    fun getStreamUrl(
        serverUrl: String,
        username: String,
        token: String,
        salt: String,
        songId: String,
        quality: String? = null,
        format: String? = null
    ): String {
        val qualityParam = quality?.let { "&maxBitRate=$it" } ?: ""
        val formatParam = format?.let { "&format=$it" } ?: ""
        return "$serverUrl/rest/stream?id=$songId&u=$username&t=$token&s=$salt$qualityParam$formatParam&v=1.16.1&c=Castafiore&f=json"
    }

    /**
     * Initialize API client
     */
    fun initialize(context: Context, baseUrl: String): CastafioreClient {
        return CastafioreClient.initialize(context, baseUrl)
    }

    /**
     * Get API service instance
     */
    fun getApiService(context: Context): CastafioreClient? {
        return CastafioreClient.getInstance(context)
    }

    /**
     * Generate authentication parameters
     */
    fun generateAuthParams(client: CastafioreClient): Triple<String, String, String> {
        return client.generateAuthParams()
    }
}
