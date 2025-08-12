package com.arantec.castafiore.data.cache

import android.content.Context
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Servicio de mantenimiento automático del cache que se ejecuta en segundo plano
 * para limpiar entradas expiradas y optimizar el rendimiento
 */
class CacheMaintenanceService private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: CacheMaintenanceService? = null

        fun getInstance(context: Context): CacheMaintenanceService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CacheMaintenanceService(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Intervalo de limpieza automática (cada 30 minutos)
        private const val CLEANUP_INTERVAL_MINUTES = 30L

        // Intervalo de estadísticas (cada 5 minutos)
        private const val STATS_INTERVAL_MINUTES = 5L
    }

    private val cacheManager = CacheManager.getInstance(context)
    private var maintenanceJob: Job? = null
    private var statsJob: Job? = null
    private val maintenanceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Listeners para estadísticas de cache
    private val cacheStatsListeners = mutableListOf<(CacheManager.CacheStats) -> Unit>()

    /**
     * Iniciar el servicio de mantenimiento automático
     */
    fun startMaintenance() {
        if (maintenanceJob?.isActive == true) {
            return // Ya está ejecutándose
        }

        maintenanceJob = maintenanceScope.launch {
            while (isActive) {
                try {
                    // Limpiar cache expirado
                    cacheManager.cleanupExpiredCache()

                    // Log para debugging (en producción se podría usar un logger más sofisticado)
                    val stats = cacheManager.getCacheStats()
                    android.util.Log.d("CacheMaintenanceService",
                        "Cache cleanup completed. Stats: Memory=${stats.memoryEntries}, " +
                        "Search=${stats.searchEntries}, Disk=${stats.diskEntries}")

                } catch (e: Exception) {
                    android.util.Log.e("CacheMaintenanceService", "Error during cache cleanup", e)
                }

                // Esperar antes del próximo ciclo de limpieza
                delay(TimeUnit.MINUTES.toMillis(CLEANUP_INTERVAL_MINUTES))
            }
        }

        // Iniciar también el reporte de estadísticas
        startStatsReporting()
    }

    /**
     * Detener el servicio de mantenimiento
     */
    fun stopMaintenance() {
        maintenanceJob?.cancel()
        statsJob?.cancel()
        maintenanceJob = null
        statsJob = null
    }

    /**
     * Iniciar reporte periódico de estadísticas
     */
    private fun startStatsReporting() {
        if (statsJob?.isActive == true) {
            return
        }

        statsJob = maintenanceScope.launch {
            while (isActive) {
                try {
                    val stats = cacheManager.getCacheStats()

                    // Notificar a los listeners
                    cacheStatsListeners.forEach { listener ->
                        try {
                            listener(stats)
                        } catch (e: Exception) {
                            android.util.Log.e("CacheMaintenanceService", "Error in stats listener", e)
                        }
                    }

                } catch (e: Exception) {
                    android.util.Log.e("CacheMaintenanceService", "Error during stats reporting", e)
                }

                delay(TimeUnit.MINUTES.toMillis(STATS_INTERVAL_MINUTES))
            }
        }
    }

    /**
     * Agregar listener para estadísticas de cache
     */
    fun addCacheStatsListener(listener: (CacheManager.CacheStats) -> Unit) {
        cacheStatsListeners.add(listener)
    }

    /**
     * Remover listener de estadísticas
     */
    fun removeCacheStatsListener(listener: (CacheManager.CacheStats) -> Unit) {
        cacheStatsListeners.remove(listener)
    }

    /**
     * Forzar limpieza inmediata del cache
     */
    suspend fun forceCleanup() {
        withContext(Dispatchers.IO) {
            try {
                cacheManager.cleanupExpiredCache()
                android.util.Log.d("CacheMaintenanceService", "Forced cache cleanup completed")
            } catch (e: Exception) {
                android.util.Log.e("CacheMaintenanceService", "Error during forced cleanup", e)
                throw e
            }
        }
    }

    /**
     * Obtener estadísticas actuales del cache
     */
    fun getCurrentStats(): CacheManager.CacheStats {
        return cacheManager.getCacheStats()
    }

    /**
     * Limpiar todo el cache (útil para debugging o cambios de configuración)
     */
    suspend fun clearAllCache() {
        withContext(Dispatchers.IO) {
            try {
                cacheManager.clearAllCache()
                android.util.Log.d("CacheMaintenanceService", "All cache cleared")
            } catch (e: Exception) {
                android.util.Log.e("CacheMaintenanceService", "Error clearing all cache", e)
                throw e
            }
        }
    }

    /**
     * Verificar si el servicio de mantenimiento está activo
     */
    fun isMaintenanceActive(): Boolean {
        return maintenanceJob?.isActive == true
    }

    /**
     * Optimización inteligente del cache basada en patrones de uso
     */
    suspend fun intelligentOptimization() {
        withContext(Dispatchers.IO) {
            try {
                val stats = cacheManager.getCacheStats()

                // Si el cache en memoria está muy lleno, forzar limpieza
                if (stats.memoryEntries > 80) {
                    cacheManager.cleanupExpiredCache()
                }

                // Si hay muchas entradas de búsqueda, limpiar las más antiguas
                if (stats.searchEntries > 40) {
                    cacheManager.cleanupExpiredCache()
                }

                android.util.Log.d("CacheMaintenanceService", "Intelligent optimization completed")

            } catch (e: Exception) {
                android.util.Log.e("CacheMaintenanceService", "Error during intelligent optimization", e)
                throw e
            }
        }
    }

    /**
     * Limpiar recursos cuando la aplicación se cierre
     */
    fun onApplicationDestroy() {
        stopMaintenance()
        maintenanceScope.cancel()
        cacheStatsListeners.clear()
    }
}
