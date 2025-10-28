package com.arantec.castafiore.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.arantec.castafiore.data.local.dao.DownloadDao
import com.arantec.castafiore.data.local.entity.DownloadEntity
import com.arantec.castafiore.data.local.entity.DownloadStatus

/**
 * Type converters for Room database
 */
class Converters {
    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String {
        return value.name
    }

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus {
        return DownloadStatus.valueOf(value)
    }
}

/**
 * Main Room database for the application
 * Contains downloads table for tracking song downloads
 */
@Database(
    entities = [DownloadEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun downloadDao(): DownloadDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        private const val DATABASE_NAME = "castafiore_database"
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration() // For initial version, can be changed later
                .build()
        }
    }
}
