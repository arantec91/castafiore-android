package com.arantec.castafiore.utils

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * Utility class for storage operations
 */
object StorageUtils {
    
    /**
     * Get available storage space in bytes
     * @param context Application context
     * @return Available space in bytes
     */
    fun getAvailableSpace(context: Context): Long {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        
        val stat = StatFs(musicDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
    
    /**
     * Check if there's enough space for downloads
     * @param context Application context
     * @param requiredBytes Required space in bytes
     * @return true if enough space is available
     */
    fun hasEnoughSpace(context: Context, requiredBytes: Long): Boolean {
        val available = getAvailableSpace(context)
        // Add 100MB buffer for safety
        val buffer = 100 * 1024 * 1024L
        return available >= (requiredBytes + buffer)
    }
    
    /**
     * Format bytes to human-readable string
     * @param bytes Size in bytes
     * @return Formatted string (e.g., "1.5 GB")
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        
        return String.format(
            "%.1f %s",
            bytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }
    
    /**
     * Get the music download directory
     * @param context Application context
     * @return Download directory
     */
    fun getMusicDownloadDirectory(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        
        if (!dir.exists()) {
            dir.mkdirs()
        }
        
        return dir
    }
}
