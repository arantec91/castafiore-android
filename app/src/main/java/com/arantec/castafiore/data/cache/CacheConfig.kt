package com.arantec.castafiore.data.cache

import android.content.Context

/**
 * Configuración y inicialización del sistema de cache
 */
object CacheConfig {

    /**
     * Inicializar el sistema de cache cuando se inicia la aplicación
     */
    fun initialize(context: Context) {
        // Inicializar el servicio de mantenimiento automático
        val maintenanceService = CacheMaintenanceService.getInstance(context)
        maintenanceService.startMaintenance()

        // Log de inicialización
        android.util.Log.d("CacheConfig", "Cache system initialized successfully")
    }

    /**
     * Configurar cache para desarrollo/debugging
     */
    fun enableDebugMode(context: Context) {
        val maintenanceService = CacheMaintenanceService.getInstance(context)

        // Agregar listener para logging de estadísticas
        maintenanceService.addCacheStatsListener { stats ->
            android.util.Log.d("CacheDebug",
                "Cache Stats - Memory: ${stats.memoryEntries}, " +
                "Search: ${stats.searchEntries}, Disk: ${stats.diskEntries}")
        }
    }

    /**
     * Limpiar cache al cerrar la aplicación
     */
    fun shutdown(context: Context) {
        val maintenanceService = CacheMaintenanceService.getInstance(context)
        maintenanceService.onApplicationDestroy()

        android.util.Log.d("CacheConfig", "Cache system shutdown completed")
    }

    /**
     * Configuración de emergencia - limpiar todo si hay problemas
     */
    suspend fun emergencyCleanup(context: Context) {
        try {
            val maintenanceService = CacheMaintenanceService.getInstance(context)
            maintenanceService.clearAllCache()

            android.util.Log.w("CacheConfig", "Emergency cache cleanup performed")
        } catch (e: Exception) {
            android.util.Log.e("CacheConfig", "Failed to perform emergency cleanup", e)
        }
    }
}
