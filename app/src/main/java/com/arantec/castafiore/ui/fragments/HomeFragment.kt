package com.arantec.castafiore.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentHomeBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.AlbumHorizontalAdapter
import com.arantec.castafiore.utils.AppLifecycleManager
import com.arantec.castafiore.utils.StatusBarUtils
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository
    private lateinit var recentlyAddedAdapter: AlbumHorizontalAdapter
    private lateinit var recentlyPlayedAdapter: AlbumHorizontalAdapter
    private lateinit var mostPlayedAdapter: AlbumHorizontalAdapter
    private lateinit var discoverAdapter: AlbumHorizontalAdapter
    private var musicService: MusicService? = null
    private var isBound = false

    // Variables para el sistema de refresh automático
    private lateinit var appLifecycleManager: AppLifecycleManager
    private var isInitialLoad = true
    private var lastLoadTime = 0L

    companion object {
        private const val TAG = "HomeFragment"
        private const val MANUAL_REFRESH_COOLDOWN = 30 * 1000L // 30 segundos entre refreshes manuales
    }

    // Listener para refresh automático
    private val autoRefreshListener = {
        android.util.Log.d(TAG, "Auto-refresh triggerizado")
        if (isAdded && _binding != null) {
            performRefresh(isAutoRefresh = true)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())
        appLifecycleManager = AppLifecycleManager.getInstance(requireContext())

        setupUI()
        setupRecyclerViews()
        setupRefreshSystem()

        // Cargar datos inmediatamente
        loadData()
    }

    private fun setupUI() {
        // Mostrar saludo dinámico
        val greeting = getGreeting()
        binding.tvGreeting.text = greeting

        // Mostrar nombre de usuario
        binding.tvUserName.text = musicRepository.username ?: "Usuario"

        // Configurar botón de búsqueda
        binding.btnSearch.setOnClickListener {
            // Navegar a la pantalla de configuraciones
            findNavController().navigate(R.id.action_home_to_settings)
        }
    }

    private fun getGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Buenos días"
            in 12..17 -> "Buenas tardes"
            in 18..23 -> "Buenas noches"
            else -> "Buenas madrugadas"
        }
    }

    private fun setupRecyclerViews() {
        // Configurar adaptador para álbumes agregados recientemente
        recentlyAddedAdapter = AlbumHorizontalAdapter { album ->
            onAlbumClick(album)
        }
        binding.rvRecentlyAdded.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentlyAddedAdapter
            // Optimizaciones de rendimiento
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
        }

        // Configurar adaptador para álbumes reproducidos recientemente
        recentlyPlayedAdapter = AlbumHorizontalAdapter { album ->
            onAlbumClick(album)
        }
        binding.rvRecentlyPlayed.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentlyPlayedAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
        }

        // Configurar adaptador para álbumes más reproducidos
        mostPlayedAdapter = AlbumHorizontalAdapter { album ->
            onAlbumClick(album)
        }
        binding.rvMostPlayed.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = mostPlayedAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
        }

        // Configurar adaptador para álbumes de descubrimiento
        discoverAdapter = AlbumHorizontalAdapter { album ->
            onAlbumClick(album)
        }
        binding.rvDiscover.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = discoverAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
        }
    }

    private fun setupRefreshSystem() {
        // Configurar pull-to-refresh si existe en el layout
        binding.swipeRefreshLayout?.let { swipeRefresh ->
            swipeRefresh.setOnRefreshListener {
                performRefresh(isManualRefresh = true)
            }

            // Configurar colores del refresh indicator
            swipeRefresh.setColorSchemeResources(
                R.color.primary,
                R.color.primary_dark,
                R.color.secondary
            )
        }

        // Registrar listener para refresh automático
        appLifecycleManager.addRefreshListener(autoRefreshListener)

        android.util.Log.d(TAG, "Sistema de refresh configurado")
        android.util.Log.d(TAG, appLifecycleManager.getDebugInfo())
    }

    /**
     * Realizar refresh de datos con diferentes estrategias
     */
    private fun performRefresh(
        isAutoRefresh: Boolean = false,
        isManualRefresh: Boolean = false,
        forceRefresh: Boolean = false
    ) {
        val currentTime = System.currentTimeMillis()

        // Verificar cooldown para refresh manual
        if (isManualRefresh && !forceRefresh) {
            val timeSinceLastLoad = currentTime - lastLoadTime
            if (timeSinceLastLoad < MANUAL_REFRESH_COOLDOWN) {
                android.util.Log.d(TAG, "Refresh manual en cooldown - ignorando")
                binding.swipeRefreshLayout?.isRefreshing = false
                return
            }
        }

        android.util.Log.d(TAG, "Iniciando refresh - Auto: $isAutoRefresh, Manual: $isManualRefresh, Force: $forceRefresh")

        lifecycleScope.launch {
            try {
                if (isAutoRefresh) {
                    // Para auto-refresh, limpiar cache selectivamente para obtener datos frescos
                    clearSelectiveCache()
                }

                if (isManualRefresh || forceRefresh) {
                    // Para refresh manual, limpiar más cache para asegurar datos completamente frescos
                    clearExtensiveCache()
                    // Invalidar específicamente las listas del Home por si acaso
                    musicRepository.invalidateHomeLists()
                }

                // Recargar datos
                loadDataInternal(isRefresh = true)
                lastLoadTime = currentTime

                android.util.Log.d(TAG, "Refresh completado exitosamente")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error durante refresh", e)
                showError("Error al actualizar datos: ${e.message}")
            } finally {
                // Ocultar indicador de refresh
                binding.swipeRefreshLayout?.isRefreshing = false
            }
        }
    }

    /**
     * Limpiar cache selectivo para auto-refresh
     */
    private fun clearSelectiveCache() {
        try {
            // Limpiar solo cache expirado para auto-refresh ligero
            musicRepository.cleanupCache()

            android.util.Log.d(TAG, "Cache selectivo limpiado para auto-refresh")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error limpiando cache selectivo", e)
        }
    }

    /**
     * Limpiar cache extensivo para refresh manual
     */
    private fun clearExtensiveCache() {
        try {
            // Limpiar TODO el cache para asegurar datos completamente frescos
            musicRepository.clearCache()

            android.util.Log.d(TAG, "Cache extensivo limpiado para refresh manual")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error limpiando cache extensivo", e)
        }
    }

    /**
     * Método interno para cargar datos con soporte para refresh
     */
    private suspend fun loadDataInternal(isRefresh: Boolean = false) {
        try {
            if (isRefresh) {
                android.util.Log.d(TAG, "Cargando datos (refresh)")
            } else {
                android.util.Log.d(TAG, "Cargando datos (inicial)")
            }

            // Para refresh, mostrar indicador sin ocultar contenido
            if (!isRefresh) {
                showLoading(true)
            }
            hideError()

            // Cargar datos secuencialmente
            loadRecentlyAddedAlbums()
            loadRecentlyPlayedAlbums()
            loadMostPlayedAlbums()
            loadDiscoverAlbums()

            if (!isRefresh) {
                showLoading(false)
            }

            android.util.Log.d(TAG, "Carga de datos completada")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error cargando datos", e)
            showError("Error al cargar contenido: ${e.localizedMessage}")
            if (!isRefresh) {
                showLoading(false)
            }
            throw e
        }
    }

    private fun loadData() {
        // Verificar si el repositorio está configurado
        if (!musicRepository.isConfigured()) {
            showError("Configura tu servidor Navidrome para ver contenido")
            return
        }

        // Asegurar que el cache se resetee si cambió server/usuario
        musicRepository.ensureCacheScope()

        // DEBUG: Agregar logs para diagnóstico
        android.util.Log.d(TAG, "Starting data load...")
        android.util.Log.d(TAG, "Server URL: ${musicRepository.serverUrl}")
        android.util.Log.d(TAG, "Username: ${musicRepository.username}")

        // IMPORTANTE: Reinicializar NavidromeClient con la URL guardada
        try {
            musicRepository.serverUrl?.let { serverUrl ->
                com.arantec.castafiore.data.network.NavidromeClient.initialize(serverUrl)
                android.util.Log.d(TAG, "NavidromeClient initialized with: $serverUrl")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing NavidromeClient: ${e.message}")
            showError("Error de configuración del servidor")
            return
        }

        lifecycleScope.launch {
            try {
                loadDataInternal(isRefresh = false)
                isInitialLoad = false
                lastLoadTime = System.currentTimeMillis()
            } catch (e: Exception) {
                // Error ya manejado en loadDataInternal
            }
        }
    }

    private suspend fun loadRecentlyAddedAlbums() {
        try {
            musicRepository.getNewestAlbums().fold(
                onSuccess = { albums ->
                    if (albums.isNotEmpty()) {
                        recentlyAddedAdapter.updateAlbums(albums.take(10))
                    } else {
                        // Si no hay álbumes nuevos, intentar cargar álbumes generales
                        loadFallbackAlbums(recentlyAddedAdapter, "newest")
                    }
                },
                onFailure = {
                    loadFallbackAlbums(recentlyAddedAdapter, "newest")
                }
            )
        } catch (e: Exception) {
            recentlyAddedAdapter.updateAlbums(emptyList())
        }
    }

    private suspend fun loadRecentlyPlayedAlbums() {
        try {
            musicRepository.getRecentlyPlayedAlbums().fold(
                onSuccess = { albums ->
                    recentlyPlayedAdapter.updateAlbums(albums.take(10))
                },
                onFailure = {
                    loadFallbackAlbums(recentlyPlayedAdapter, "recent")
                }
            )
        } catch (e: Exception) {
            recentlyPlayedAdapter.updateAlbums(emptyList())
        }
    }

    private suspend fun loadMostPlayedAlbums() {
        try {
            musicRepository.getMostPlayedAlbums().fold(
                onSuccess = { albums ->
                    mostPlayedAdapter.updateAlbums(albums.take(10))
                },
                onFailure = {
                    loadFallbackAlbums(mostPlayedAdapter, "frequent")
                }
            )
        } catch (e: Exception) {
            mostPlayedAdapter.updateAlbums(emptyList())
        }
    }

    private suspend fun loadDiscoverAlbums() {
        try {
            // Usar la API de Navidrome para obtener álbumes aleatorios reales
            musicRepository.getRandomAlbums(30).fold(
                onSuccess = { albums: List<Album> ->
                    // Tomar hasta 10 álbumes únicos aleatorios de la respuesta
                    discoverAdapter.updateAlbums(albums.take(10))
                },
                onFailure = { error ->
                    // Si falla la API de álbumes aleatorios, usar fallback
                    loadFallbackDiscoverAlbums()
                }
            )
        } catch (e: Exception) {
            // En caso de excepción, usar fallback
            loadFallbackDiscoverAlbums()
        }
    }

    private suspend fun loadFallbackDiscoverAlbums() {
        try {
            // Como fallback, obtener álbumes generales y mezclarlos
            musicRepository.getAlbums().fold(
                onSuccess = { albums: List<Album> ->
                    // Mezclar y tomar hasta 10 álbumes para la sección discover
                    discoverAdapter.updateAlbums(albums.shuffled().take(10))
                },
                onFailure = {
                    // Último recurso: lista vacía
                    discoverAdapter.updateAlbums(emptyList())
                }
            )
        } catch (e: Exception) {
            discoverAdapter.updateAlbums(emptyList())
        }
    }

    private suspend fun loadFallbackAlbums(adapter: AlbumHorizontalAdapter, type: String) {
        try {
            // Como fallback, cargar álbumes generales
            musicRepository.getAlbums().fold(
                onSuccess = { albums ->
                    val limitedAlbums = when (type) {
                        "newest" -> albums.take(10)
                        "recent" -> albums.shuffled().take(10)
                        "frequent" -> albums.sortedByDescending { it.playCount ?: 0 }.take(10)
                        else -> albums.take(10)
                    }
                    adapter.updateAlbums(limitedAlbums)
                },
                onFailure = {
                    adapter.updateAlbums(emptyList())
                }
            )
        } catch (e: Exception) {
            adapter.updateAlbums(emptyList())
        }
    }

    private fun onAlbumClick(album: Album) {
        // Por ahora, navegar usando Bundle hasta que se generen las clases de Navigation
        val bundle = Bundle().apply {
            putParcelable("album", album)
        }
        findNavController().navigate(R.id.albumDetailFragment, bundle)
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE

        // Ocultar/mostrar el contenido de las secciones cuando hay loading
        val contentVisibility = if (show) View.GONE else View.VISIBLE

        // Ocultar títulos de secciones usando binding directamente
        binding.tvRecentlyAddedTitle.visibility = contentVisibility
        binding.tvRecentlyPlayedTitle.visibility = contentVisibility
        binding.tvMostPlayedTitle.visibility = contentVisibility
        binding.tvDiscoverTitle.visibility = contentVisibility

        // Ocultar RecyclerViews
        binding.rvRecentlyAdded.visibility = contentVisibility
        binding.rvRecentlyPlayed.visibility = contentVisibility
        binding.rvMostPlayed.visibility = contentVisibility
        binding.rvDiscover.visibility = contentVisibility
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        // Conectar al servicio de música
        Intent(context, MusicService::class.java).also { intent ->
            context?.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Aplicar el color estático consistente de la app
        StatusBarUtils.setStatusBarColor(this)
    }

    override fun onStop() {
        super.onStop()
        // Desconectar del servicio
        if (isBound) {
            context?.unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Remover listener para evitar memory leaks
        appLifecycleManager.removeRefreshListener(autoRefreshListener)

        _binding = null
    }
}
