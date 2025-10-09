package com.arantec.castafiore.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Manages caching for the application using SharedPreferences
 */
class CacheManager private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("castafiore_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        // Cache TTL constants (in milliseconds)
        const val DEFAULT_TTL = 30 * 60 * 1000L // 30 minutes
        const val FAVORITE_TTL = 60 * 60 * 1000L // 1 hour
        const val SEARCH_TTL = 15 * 60 * 1000L // 15 minutes
        const val ARTIST_TTL = 60 * 60 * 1000L // 1 hour

        private const val TTL_SUFFIX = "_ttl"

        @Volatile
        private var INSTANCE: CacheManager? = null

        fun getInstance(context: Context): CacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CacheManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Data class for cache statistics
     */
    data class CacheStats(
        val memoryEntries: Int,
        val searchEntries: Int,
        val diskEntries: Int,
        val totalSize: Int
    )

    /**
     * Store data in cache with TTL
     */
    fun <T> putCache(key: String, data: T, type: Type, ttl: Long = DEFAULT_TTL) {
        try {
            val json = gson.toJson(data, type)
            val expirationTime = System.currentTimeMillis() + ttl

            sharedPreferences.edit()
                .putString(key, json)
                .putLong(key + TTL_SUFFIX, expirationTime)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get data from cache
     */
    fun <T> getCache(key: String, type: Type): T? {
        try {
            // Check if cache has expired
            val expirationTime = sharedPreferences.getLong(key + TTL_SUFFIX, 0)
            if (System.currentTimeMillis() > expirationTime) {
                invalidateCache(key)
                return null
            }

            val json = sharedPreferences.getString(key, null) ?: return null
            return gson.fromJson(json, type)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Check if cache exists and is valid
     */
    fun isCacheValid(key: String): Boolean {
        val expirationTime = sharedPreferences.getLong(key + TTL_SUFFIX, 0)
        return System.currentTimeMillis() <= expirationTime &&
               sharedPreferences.contains(key)
    }

    /**
     * Invalidate specific cache entry
     */
    fun invalidateCache(key: String) {
        sharedPreferences.edit()
            .remove(key)
            .remove(key + TTL_SUFFIX)
            .apply()
    }

    /**
     * Clear all cache
     */
    fun clearAllCache() {
        sharedPreferences.edit().clear().apply()
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheStats {
        val allKeys = getAllCacheKeys()
        val memoryEntries = allKeys.count { !it.startsWith("search_") && !it.startsWith("disk_") }
        val searchEntries = allKeys.count { it.startsWith("search_") }
        val diskEntries = allKeys.count { it.startsWith("disk_") }

        return CacheStats(
            memoryEntries = memoryEntries,
            searchEntries = searchEntries,
            diskEntries = diskEntries,
            totalSize = allKeys.size
        )
    }

    /**
     * Clean expired cache entries
     */
    fun cleanupExpiredCache() {
        cleanupCache() // Reuse existing cleanup method
    }

    /**
     * Clean expired cache entries
     */
    fun cleanupCache() {
        val currentTime = System.currentTimeMillis()
        val editor = sharedPreferences.edit()
        val keysToRemove = mutableListOf<String>()

        sharedPreferences.all.forEach { (key, _) ->
            if (key.endsWith(TTL_SUFFIX)) {
                val baseKey = key.removeSuffix(TTL_SUFFIX)
                val expirationTime = sharedPreferences.getLong(key, 0)

                if (currentTime > expirationTime) {
                    keysToRemove.add(baseKey)
                    keysToRemove.add(key)
                }
            }
        }

        keysToRemove.forEach { key ->
            editor.remove(key)
        }

        editor.apply()
    }

    /**
     * Get cache size (number of entries)
     */
    fun getCacheSize(): Int {
        return sharedPreferences.all.size / 2 // Divide by 2 because each entry has a TTL entry
    }

    /**
     * Get all cache keys
     */
    fun getAllCacheKeys(): Set<String> {
        return sharedPreferences.all.keys.filter { !it.endsWith(TTL_SUFFIX) }.toSet()
    }
}
