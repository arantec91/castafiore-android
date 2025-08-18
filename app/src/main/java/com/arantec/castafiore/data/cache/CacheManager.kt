package com.arantec.castafiore.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Sistema de cache híbrido que combina cache en memoria y en disco
 * con estrategias de TTL (Time To Live) para optimizar las llamadas a la API
 */
class CacheManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: CacheManager? = null

        fun getInstance(context: Context): CacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CacheManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // TTL (Time To Live) en milisegundos para diferentes tipos de contenido
        private const val SONGS_TTL = 5 * 60 * 1000L           // 5 minutos
        private const val ALBUMS_TTL = 10 * 60 * 1000L         // 10 minutos
        private const val ARTISTS_TTL = 15 * 60 * 1000L        // 15 minutos
        private const val ALBUM_DETAIL_TTL = 30 * 60 * 1000L   // 30 minutos
        private const val ARTIST_DETAIL_TTL = 30 * 60 * 1000L  // 30 minutos
        private const val SEARCH_TTL = 2 * 60 * 1000L          // 2 minutos
        private const val PLAYLISTS_TTL = 5 * 60 * 1000L       // 5 minutos
        private const val RANDOM_SONGS_TTL = 1 * 60 * 1000L    // 1 minuto (cambian frecuentemente)

        // Tamaños máximos de cache en memoria
        private const val MAX_MEMORY_CACHE_SIZE = 100
        private const val MAX_SEARCH_CACHE_SIZE = 50
    }

    private val gson = Gson()
    private val diskCache: SharedPreferences = context.getSharedPreferences("navidrome_cache", Context.MODE_PRIVATE)

    // Cache en memoria con acceso concurrente seguro
    private val memoryCache = ConcurrentHashMap<String, CacheEntry<Any>>()
    private val searchCache = ConcurrentHashMap<String, CacheEntry<Any>>()

    /**
     * Entrada de cache que incluye los datos y metadata de expiración
     */
    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long,
        val ttl: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttl
    }

    /**
     * Estrategia de cache: buscar primero en memoria, luego en disco, finalmente llamar al proveedor
     */
    suspend fun <T> getOrFetch(
        key: String,
        ttl: Long,
        type: Type,
        provider: suspend () -> Result<T>
    ): Result<T> {
        return try {
            // 1. Intentar obtener desde cache en memoria
            val memoryResult = getFromMemory<T>(key)
            if (memoryResult != null) {
                return Result.success(memoryResult)
            }

            // 2. Intentar obtener desde cache en disco
            val diskResult = getFromDisk<T>(key, ttl, type)
            if (diskResult != null) {
                // Guardar en memoria para futuras consultas
                putInMemory(key, diskResult, ttl)
                return Result.success(diskResult)
            }

            // 3. Cache miss - obtener datos frescos del proveedor
            val result = provider()
            result.onSuccess { data ->
                // Guardar en ambos caches
                putInMemory(key, data, ttl)
                putOnDisk(key, data)
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Cache específico para búsquedas con TTL corto
     */
    suspend fun <T> getOrFetchSearch(
        query: String,
        type: Type,
        provider: suspend () -> Result<T>
    ): Result<T> {
        return try {
            val key = "search_$query"

            // Buscar en cache de búsquedas
            val cached = searchCache[key]
            if (cached != null && !cached.isExpired()) {
                @Suppress("UNCHECKED_CAST")
                return Result.success(cached.data as T)
            }

            // Cache miss - obtener datos frescos
            val result = provider()
            result.onSuccess { data ->
                // Guardar en cache de búsquedas con TTL corto
                searchCache[key] = CacheEntry(data as Any, System.currentTimeMillis(), SEARCH_TTL)

                // Limpiar cache de búsquedas si está muy lleno
                if (searchCache.size > MAX_SEARCH_CACHE_SIZE) {
                    clearExpiredSearchEntries()
                }
            }
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun <T> getFromMemory(key: String): T? {
        val entry = memoryCache[key]
        return if (entry != null && !entry.isExpired()) {
            @Suppress("UNCHECKED_CAST")
            entry.data as T
        } else {
            // Remover entrada expirada
            if (entry != null) {
                memoryCache.remove(key)
            }
            null
        }
    }

    private fun <T> getFromDisk(key: String, ttl: Long, type: Type): T? {
        return try {
            val jsonData = diskCache.getString(key, null)
            val timestamp = diskCache.getLong("${key}_timestamp", 0)

            if (jsonData != null && timestamp > 0) {
                val isExpired = System.currentTimeMillis() - timestamp > ttl
                if (!isExpired) {
                    gson.fromJson<T>(jsonData, type)
                } else {
                    // Limpiar entrada expirada
                    diskCache.edit()
                        .remove(key)
                        .remove("${key}_timestamp")
                        .apply()
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun <T> putInMemory(key: String, data: T, ttl: Long) {
        memoryCache[key] = CacheEntry(data as Any, System.currentTimeMillis(), ttl)

        // Limpiar cache si está muy lleno
        if (memoryCache.size > MAX_MEMORY_CACHE_SIZE) {
            clearExpiredMemoryEntries()
        }
    }

    private fun <T> putOnDisk(key: String, data: T) {
        try {
            val jsonData = gson.toJson(data)
            diskCache.edit()
                .putString(key, jsonData)
                .putLong("${key}_timestamp", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // Log error pero no fallar
        }
    }

    private fun clearExpiredMemoryEntries() {
        val iterator = memoryCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) {
                iterator.remove()
            }
        }
    }

    private fun clearExpiredSearchEntries() {
        val iterator = searchCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) {
                iterator.remove()
            }
        }
    }

    /**
     * Métodos de utilidad para diferentes tipos de contenido con TTL específicos
     */
    suspend fun <T> getSongs(key: String, type: Type, provider: suspend () -> Result<T>): Result<T> {
        return getOrFetch(key, SONGS_TTL, type, provider)
    }

    suspend fun <T> getAlbums(key: String, type: Type, provider: suspend () -> Result<T>): Result<T> {
        return getOrFetch(key, ALBUMS_TTL, type, provider)
    }

    suspend fun <T> getArtists(key: String, type: Type, provider: suspend () -> Result<T>): Result<T> {
        return getOrFetch(key, ARTISTS_TTL, type, provider)
    }

    suspend fun <T> getAlbumDetail(albumId: String, type: Type, provider: suspend () -> Result<T>): Result<T> {
        return getOrFetch("album_detail_$albumId", ALBUM_DETAIL_TTL, type, provider)
    }

    suspend fun <T> getArtistDetail(artistId: String, type: Type, provider: suspend () -> Result<T>): Result<T> {
        return getOrFetch("artist_detail_$artistId", ARTIST_DETAIL_TTL, type, provider)
    }

    suspend fun <T> getPlaylists(key: String, type: Type, provider: suspend () -> Result<T>): Result<T> {
        return getOrFetch(key, PLAYLISTS_TTL, type, provider)
    }

    suspend fun <T> getRandomSongs(key: String, type: Type, provider: suspend () -> Result<T>): Result<T> {
        return getOrFetch(key, RANDOM_SONGS_TTL, type, provider)
    }

    suspend fun <T> getRandomAlbums(key: String, type: Type, provider: suspend () -> Result<T>): Result<T> {
        return getOrFetch(key, RANDOM_SONGS_TTL, type, provider)
    }

    /**
     * Invalidar cache específico
     */
    fun invalidateCache(key: String) {
        memoryCache.remove(key)
        diskCache.edit()
            .remove(key)
            .remove("${key}_timestamp")
            .apply()
    }

    /**
     * Invalidar todo el cache (útil al cambiar de servidor o usuario)
     */
    fun clearAllCache() {
        memoryCache.clear()
        searchCache.clear()
        diskCache.edit().clear().apply()
    }

    /**
     * Limpiar solo cache expirado
     */
    fun cleanupExpiredCache() {
        clearExpiredMemoryEntries()
        clearExpiredSearchEntries()

        // Limpiar cache en disco expirado (operación costosa, hacer esporádicamente)
        val editor = diskCache.edit()
        val allKeys = diskCache.all.keys

        allKeys.forEach { key ->
            if (key.endsWith("_timestamp")) {
                val dataKey = key.removeSuffix("_timestamp")
                val timestamp = diskCache.getLong(key, 0)

                // Usar TTL más conservador para limpeza general
                if (System.currentTimeMillis() - timestamp > ALBUM_DETAIL_TTL) {
                    editor.remove(dataKey)
                    editor.remove(key)
                }
            }
        }

        editor.apply()
    }

    /**
     * Obtener estadísticas del cache para debugging
     */
    fun getCacheStats(): CacheStats {
        val memorySize = memoryCache.size
        val searchSize = searchCache.size
        val diskSize = diskCache.all.size / 2 // Dividir por 2 porque guardamos datos + timestamp

        return CacheStats(
            memoryEntries = memorySize,
            searchEntries = searchSize,
            diskEntries = diskSize
        )
    }

    data class CacheStats(
        val memoryEntries: Int,
        val searchEntries: Int,
        val diskEntries: Int
    )

    /**
     * Peek sincronamente en el cache (memoria o disco) sin llamar al proveedor.
     * Respeta TTL. Devuelve null si no hay dato válido.
     */
    fun <T> peek(key: String, ttl: Long, type: Type): T? {
        // Primero memoria
        val mem = getFromMemory<T>(key)
        if (mem != null) return mem
        // Luego disco con validación de TTL
        return getFromDisk<T>(key, ttl, type)
    }
}
