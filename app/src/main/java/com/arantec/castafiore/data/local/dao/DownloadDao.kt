package com.arantec.castafiore.data.local.dao

import androidx.room.*
import com.arantec.castafiore.data.local.entity.DownloadEntity
import com.arantec.castafiore.data.local.entity.DownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for download operations
 * Provides Flow-based reactive queries for real-time updates
 */
@Dao
interface DownloadDao {
    
    /**
     * Observe all active downloads (PENDING or DOWNLOADING)
     * Ordered by addedDate ascending for FIFO queue processing
     */
    @Query("SELECT * FROM downloads WHERE status IN ('PENDING', 'DOWNLOADING') ORDER BY addedDate ASC")
    fun observeActiveDownloads(): Flow<List<DownloadEntity>>
    
    /**
     * Observe all downloads for a specific album
     */
    @Query("SELECT * FROM downloads WHERE albumId = :albumId")
    fun observeAlbumDownloads(albumId: String): Flow<List<DownloadEntity>>
    
    /**
     * Get all completed downloads
     */
    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedDate DESC")
    fun observeCompletedDownloads(): Flow<List<DownloadEntity>>
    
    /**
     * Get a specific download by songId
     */
    @Query("SELECT * FROM downloads WHERE songId = :songId")
    suspend fun getDownload(songId: String): DownloadEntity?
    
    /**
     * Insert or replace a download
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)
    
    /**
     * Insert multiple downloads
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(downloads: List<DownloadEntity>)
    
    /**
     * Update an existing download
     */
    @Update
    suspend fun update(download: DownloadEntity)
    
    /**
     * Delete a download by songId
     */
    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun delete(songId: String)
    
    /**
     * Delete all downloads for an album
     */
    @Query("DELETE FROM downloads WHERE albumId = :albumId")
    suspend fun deleteAlbum(albumId: String)
    
    /**
     * Delete all completed downloads
     */
    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun deleteAllCompleted()
    
    /**
     * Delete all failed or cancelled downloads
     */
    @Query("DELETE FROM downloads WHERE status IN ('FAILED', 'CANCELLED')")
    suspend fun deleteFailedAndCancelled()
    
    /**
     * Get count of active downloads
     */
    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('PENDING', 'DOWNLOADING')")
    suspend fun getActiveDownloadCount(): Int
    
    /**
     * Get pending downloads (for queue processing)
     */
    @Query("SELECT * FROM downloads WHERE status = 'PENDING' ORDER BY addedDate ASC LIMIT :limit")
    suspend fun getPendingDownloads(limit: Int): List<DownloadEntity>
    
    /**
     * Get all downloading items
     */
    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING'")
    suspend fun getDownloadingItems(): List<DownloadEntity>
}
