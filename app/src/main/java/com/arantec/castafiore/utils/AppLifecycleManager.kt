package com.arantec.castafiore.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.TimeUnit

/**
 * Utilidad para detectar cuando la app se cierra y vuelve a abrirse
 * y gestionar refreshes automáticos de datos
 */
class AppLifecycleManager private constructor(private val context: Context) : DefaultLifecycleObserver {

    companion object {
        @Volatile
        private var INSTANCE: AppLifecycleManager? = null

        fun getInstance(context: Context): AppLifecycleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppLifecycleManager(context.applicationContext).also {
                    INSTANCE = it
                    // Registrar observer del ciclo de vida de la app
                    ProcessLifecycleOwner.get().lifecycle.addObserver(it)
                }
            }
        }

        // Configuraciones de refresh
        private const val MIN_TIME_BETWEEN_REFRESHES = 5 * 60 * 1000L // 5 minutos
        private const val FORCE_REFRESH_AFTER = 30 * 60 * 1000L // 30 minutos
        private const val PREFS_NAME = "app_lifecycle_prefs"
        private const val KEY_LAST_BACKGROUND_TIME = "last_background_time"
        private const val KEY_LAST_HOME_REFRESH = "last_home_refresh"
        private const val KEY_APP_WAS_IN_BACKGROUND = "app_was_in_background"
        private const val KEY_FIRST_LAUNCH_AFTER_BACKGROUND = "first_launch_after_background"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val refreshListeners = mutableListOf<() -> Unit>()

    private var appWasInBackground = false
    private var isFirstLaunchAfterBackground = false

    init {
        // Recuperar estado previo
        appWasInBackground = prefs.getBoolean(KEY_APP_WAS_IN_BACKGROUND, false)
        isFirstLaunchAfterBackground = prefs.getBoolean(KEY_FIRST_LAUNCH_AFTER_BACKGROUND, false)
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        android.util.Log.d("AppLifecycleManager", "App viene al foreground")

        val currentTime = System.currentTimeMillis()
        val lastBackgroundTime = prefs.getLong(KEY_LAST_BACKGROUND_TIME, 0)
        val lastHomeRefresh = prefs.getLong(KEY_LAST_HOME_REFRESH, 0)

        // Verificar si necesitamos hacer refresh
        val timeInBackground = currentTime - lastBackgroundTime
        val timeSinceLastRefresh = currentTime - lastHomeRefresh

        val shouldRefresh = when {
            // Primera vez después de estar en background
            appWasInBackground && !isFirstLaunchAfterBackground -> {
                android.util.Log.d("AppLifecycleManager", "Primera vez después de background - refresh necesario")
                markFirstLaunchAfterBackground()
                true
            }
            // Tiempo suficiente en background (más de 5 minutos)
            timeInBackground > MIN_TIME_BETWEEN_REFRESHES -> {
                android.util.Log.d("AppLifecycleManager", "Tiempo en background: ${timeInBackground}ms - refresh necesario")
                true
            }
            // Mucho tiempo desde el último refresh (más de 30 minutos)
            timeSinceLastRefresh > FORCE_REFRESH_AFTER -> {
                android.util.Log.d("AppLifecycleManager", "Mucho tiempo desde último refresh: ${timeSinceLastRefresh}ms - refresh forzado")
                true
            }
            else -> {
                android.util.Log.d("AppLifecycleManager", "No es necesario refresh - tiempo en background: ${timeInBackground}ms")
                false
            }
        }

        if (shouldRefresh) {
            triggerRefresh()
        }

        // Limpiar flag de background
        appWasInBackground = false
        prefs.edit().putBoolean(KEY_APP_WAS_IN_BACKGROUND, false).apply()
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        android.util.Log.d("AppLifecycleManager", "App va al background")

        val currentTime = System.currentTimeMillis()
        appWasInBackground = true

        prefs.edit()
            .putLong(KEY_LAST_BACKGROUND_TIME, currentTime)
            .putBoolean(KEY_APP_WAS_IN_BACKGROUND, true)
            .putBoolean(KEY_FIRST_LAUNCH_AFTER_BACKGROUND, false)
            .apply()
    }

    private fun markFirstLaunchAfterBackground() {
        isFirstLaunchAfterBackground = true
        prefs.edit()
            .putBoolean(KEY_FIRST_LAUNCH_AFTER_BACKGROUND, true)
            .apply()
    }

    private fun triggerRefresh() {
        android.util.Log.d("AppLifecycleManager", "Triggerando refresh a ${refreshListeners.size} listeners")

        // Marcar timestamp del refresh
        prefs.edit()
            .putLong(KEY_LAST_HOME_REFRESH, System.currentTimeMillis())
            .apply()

        // Notificar a todos los listeners
        refreshListeners.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                android.util.Log.e("AppLifecycleManager", "Error en refresh listener", e)
            }
        }
    }

    /**
     * Registrar listener para refresh automático
     */
    fun addRefreshListener(listener: () -> Unit) {
        refreshListeners.add(listener)
        android.util.Log.d("AppLifecycleManager", "Listener agregado - total: ${refreshListeners.size}")
    }

    /**
     * Remover listener
     */
    fun removeRefreshListener(listener: () -> Unit) {
        refreshListeners.remove(listener)
        android.util.Log.d("AppLifecycleManager", "Listener removido - total: ${refreshListeners.size}")
    }

    /**
     * Verificar si debería hacerse refresh basado en tiempo
     */
    fun shouldRefreshBasedOnTime(): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastRefresh = prefs.getLong(KEY_LAST_HOME_REFRESH, 0)
        val timeSinceLastRefresh = currentTime - lastRefresh

        return timeSinceLastRefresh > MIN_TIME_BETWEEN_REFRESHES
    }

    /**
     * Forzar refresh manual
     */
    fun forceRefresh() {
        android.util.Log.d("AppLifecycleManager", "Refresh manual forzado")
        triggerRefresh()
    }

    /**
     * Obtener tiempo desde el último refresh
     */
    fun getTimeSinceLastRefresh(): Long {
        val currentTime = System.currentTimeMillis()
        val lastRefresh = prefs.getLong(KEY_LAST_HOME_REFRESH, 0)
        return currentTime - lastRefresh
    }

    /**
     * Obtener estadísticas para debugging
     */
    fun getDebugInfo(): String {
        val currentTime = System.currentTimeMillis()
        val lastBackgroundTime = prefs.getLong(KEY_LAST_BACKGROUND_TIME, 0)
        val lastHomeRefresh = prefs.getLong(KEY_LAST_HOME_REFRESH, 0)
        val timeSinceBackground = currentTime - lastBackgroundTime
        val timeSinceRefresh = currentTime - lastHomeRefresh

        return """
            AppLifecycleManager Debug Info:
            - App was in background: $appWasInBackground
            - First launch after background: $isFirstLaunchAfterBackground
            - Time since background: ${timeSinceBackground}ms (${TimeUnit.MILLISECONDS.toMinutes(timeSinceBackground)} min)
            - Time since last refresh: ${timeSinceRefresh}ms (${TimeUnit.MILLISECONDS.toMinutes(timeSinceRefresh)} min)
            - Registered listeners: ${refreshListeners.size}
        """.trimIndent()
    }
}
