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
import androidx.recyclerview.widget.RecyclerView
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentHomeBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.AlbumHorizontalAdapter
import com.arantec.castafiore.ui.adapters.ArtistHorizontalAdapter
import com.arantec.castafiore.utils.AppLifecycleManager
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.ImageLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.arantec.castafiore.ui.helpers.HasContentState
import com.arantec.castafiore.ui.helpers.LoadingHost

class HomeFragment : Fragment(), HasContentState {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Referencia opcional al host con overlay global
    private var loadingHost: LoadingHost? = null

    private lateinit var musicRepository: MusicRepository
    private lateinit var recentlyAddedAdapter: AlbumHorizontalAdapter
    private lateinit var recentlyPlayedAdapter: AlbumHorizontalAdapter
    private lateinit var mostPlayedAdapter: AlbumHorizontalAdapter
    private lateinit var similarArtistsAdapter: ArtistHorizontalAdapter
    private lateinit var favoriteArtistsAdapter: ArtistHorizontalAdapter
    private var musicService: MusicService? = null
    private var isBound = false

    // Flags para evitar registrar observers múltiples veces al recrear la vista
    private var registeredRecentlyAddedObserver = false
    private var registeredRecentlyPlayedObserver = false
    private var registeredMostPlayedObserver = false

    // Variables para el sistema de refresh automático
    private lateinit var appLifecycleManager: AppLifecycleManager
    private var isInitialLoad = true
    private var lastLoadTime = 0L
    private var attemptedFavoriteRetry = false

    // Listener para refresh automático
    private val autoRefreshListener = {
        android.util.Log.d("HomeFragment", "Auto-refresh triggerizado")
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

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Detectar si el host expone overlay global
        loadingHost = (context as? LoadingHost) ?: (parentFragment as? LoadingHost)
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

        // Asegurar que el contenido no se dibuje bajo la status bar, sin añadir insets inferiores
        StatusBarUtils.applyStatusBarTopPadding(binding.root)

        setupUI()
        setupRecyclerViews()
        setupRefreshSystem()

        // Establecer estado de loading antes de aplicar cualquier cache para evitar parpadeo desordenado
        showLoading(true)

        // Prefijar sliders base desde cache si existen (Discover eliminado)
        applyCachedRecentlyAddedIfAvailable()
        // No forzar visibilidad aquí; el flujo de loading priorizará la sección superior (favoritos)

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
        // Pool compartido para mejorar el reciclado entre listas horizontales
        val sharedPool = RecyclerView.RecycledViewPool()

        // Configurar adaptador para álbumes agregados recientemente (reusar si ya existe)
        if (!this::recentlyAddedAdapter.isInitialized) {
            recentlyAddedAdapter = AlbumHorizontalAdapter { album -> onAlbumClick(album) }
        }
        binding.rvRecentlyAdded.apply {
            val lm = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            lm.isItemPrefetchEnabled = true
            lm.initialPrefetchItemCount = 6
            layoutManager = lm
            adapter = recentlyAddedAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
            setRecycledViewPool(sharedPool)
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        // Observer para asegurar visibilidad del slider base al actualizarse (registrar una sola vez)
        if (!registeredRecentlyAddedObserver) {
            recentlyAddedAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                private fun ensureVisible() { binding.root.post { ensureBaseSectionsVisible() } }
                override fun onChanged() = ensureVisible()
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = ensureVisible()
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = ensureVisible()
            })
            registeredRecentlyAddedObserver = true
        }

        // Configurar adaptador para álbumes reproducidos recientemente (reusar si ya existe)
        if (!this::recentlyPlayedAdapter.isInitialized) {
            recentlyPlayedAdapter = AlbumHorizontalAdapter { album -> onAlbumClick(album) }
        }
        if (!registeredRecentlyPlayedObserver) {
            recentlyPlayedAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { binding.root.post { updateOptionalSectionsVisibility() } }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { binding.root.post { updateOptionalSectionsVisibility() } }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { binding.root.post { updateOptionalSectionsVisibility() } }
            })
            registeredRecentlyPlayedObserver = true
        }
        binding.rvRecentlyPlayed.apply {
            val lm = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            lm.isItemPrefetchEnabled = true
            lm.initialPrefetchItemCount = 6
            layoutManager = lm
            adapter = recentlyPlayedAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
            setRecycledViewPool(sharedPool)
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }

        // Configurar adaptador para álbumes más reproducidos (reusar si ya existe)
        if (!this::mostPlayedAdapter.isInitialized) {
            mostPlayedAdapter = AlbumHorizontalAdapter { album -> onAlbumClick(album) }
        }
        if (!registeredMostPlayedObserver) {
            mostPlayedAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { binding.root.post { updateOptionalSectionsVisibility() } }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { binding.root.post { updateOptionalSectionsVisibility() } }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { binding.root.post { updateOptionalSectionsVisibility() } }
            })
            registeredMostPlayedObserver = true
        }
        binding.rvMostPlayed.apply {
            val lm = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            lm.isItemPrefetchEnabled = true
            lm.initialPrefetchItemCount = 6
            layoutManager = lm
            adapter = mostPlayedAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
            setRecycledViewPool(sharedPool)
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }

        // Configurar adaptador para artistas similares (reusar si ya existe)
        if (!this::similarArtistsAdapter.isInitialized) {
            similarArtistsAdapter = ArtistHorizontalAdapter(
                onArtistClick = { artist -> onArtistClick(artist) },
                imageSizeDp = 128,
                textWidthDp = 128,
                textSizeSp = 14f,
                centerText = true
            )
        }
        binding.rvSimilarArtists.apply {
            val lm = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            lm.isItemPrefetchEnabled = true
            lm.initialPrefetchItemCount = 6
            layoutManager = lm
            adapter = similarArtistsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
            setRecycledViewPool(sharedPool)
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }

        // Configurar adaptador para Tus artistas favoritos (reusar si ya existe)
        if (!this::favoriteArtistsAdapter.isInitialized) {
            favoriteArtistsAdapter = ArtistHorizontalAdapter(
                onArtistClick = { artist -> onArtistClick(artist) },
                imageSizeDp = 96,
                textWidthDp = 96,
                textSizeSp = 14f,
                centerText = true
            )
        }
        binding.rvFavoriteArtists.apply {
            val lm = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            lm.isItemPrefetchEnabled = true
            lm.initialPrefetchItemCount = 6
            layoutManager = lm
            adapter = favoriteArtistsAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            isNestedScrollingEnabled = false
            setRecycledViewPool(sharedPool)
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }

    private fun setupRefreshSystem() {
        // Configurar pull-to-refresh
        val swipeRefresh = binding.swipeRefreshLayout
        swipeRefresh.setOnRefreshListener {
            performRefresh(isManualRefresh = true)
        }
        // Configurar colores del refresh indicador
        swipeRefresh.setColorSchemeResources(
            R.color.primary,
            R.color.primary_dark,
            R.color.secondary
        )

        // Registrar listener para refresh automático
        appLifecycleManager.addRefreshListener(autoRefreshListener)

        android.util.Log.d("HomeFragment", "Sistema de refresh configurado")
        android.util.Log.d("HomeFragment", appLifecycleManager.getDebugInfo())
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
            if (timeSinceLastLoad < 30_000L) {
                android.util.Log.d("HomeFragment", "Refresh manual en cooldown - ignorando")
                binding.swipeRefreshLayout.isRefreshing = false
                return
            }
        }

        android.util.Log.d("HomeFragment", "Iniciando refresh - Auto: $isAutoRefresh, Manual: $isManualRefresh, Force: $forceRefresh")

        // Usar lifecycle del view para evitar fugas cuando se destruye la vista
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isAutoRefresh) {
                    // Para auto-refresh, limpiar cache selectivamente para obtener datos frescos
                    clearSelectiveCache()
                }

                // Importante: no limpiar agresivamente ni borrar caches en memoria durante auto-refresh,
                // incluso si viene con forceRefresh. Mantener UI estable hasta que lleguen datos nuevos.
                if (!isAutoRefresh && (isManualRefresh || forceRefresh)) {
                    // Para refresh manual/forzado explícito (no automático), limpiar más cache
                    clearExtensiveCache()
                    // Invalidar específicamente las listas del Home por si acaso
                    musicRepository.invalidateHomeLists()
                    // Limpiar caches en memoria
                    cachedRecentlyAdded = null
                    cachedRecentlyAddedTime = 0L
                }

                // Recargar datos
                loadDataInternal(isRefresh = true)
                lastLoadTime = currentTime

                android.util.Log.d("HomeFragment", "Refresh completado exitosamente")

            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "Error durante refresh", e)
                showError("Error al actualizar datos: ${e.message}")
            } finally {
                // Ocultar indicador de refresh
                binding.swipeRefreshLayout.isRefreshing = false
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

            android.util.Log.d("HomeFragment", "Cache selectivo limpiado para auto-refresh")
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "Error limpiando cache selectivo", e)
        }
    }

    /**
     * Limpiar cache extensivo para refresh manual
     */
    private fun clearExtensiveCache() {
        try {
            // Limpiar TODO el cache para asegurar datos completamente frescos
            musicRepository.clearCache()

            android.util.Log.d("HomeFragment", "Cache extensivo limpiado para refresh manual")
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "Error limpiando cache extensivo", e)
        }
    }

    /**
     * Método interno para cargar datos con soporte para refresh
     */
    private suspend fun loadDataInternal(isRefresh: Boolean = false) {
        try {
            if (isRefresh) {
                android.util.Log.d("HomeFragment", "Cargando datos (refresh)")
            } else {
                android.util.Log.d("HomeFragment", "Cargando datos (inicial)")
                // Siempre mostrar loading al entrar para evitar que "Agregados recientemente" aparezca antes que la sección superior
                withContext(Dispatchers.Main) { showLoading(true) }
            }

            // Cargar sección superior primero (favoritos) para mejorar la percepción de orden
            try {
                loadFavoriteArtists()
            } catch (_: Exception) { }

            // Cargar el resto en paralelo
            supervisorScope {
                awaitAll(
                    async { loadRecentlyAddedAlbums() },
                    async { loadRecentlyPlayedAlbums() },
                    async { loadMostPlayedAlbums() }
                )
            }

            // Asegurar actualización de visibilidad en el hilo principal tras los cambios
            withContext(Dispatchers.Main) {
                updateOptionalSectionsVisibility()
                if (!isRefresh) showLoading(false)
            }

            // Ahora cargar la sección de Artistas similares (no mostrar durante ProgressBar)
            try {
                loadSimilarArtists()
            } catch (_: Exception) {
                // Ignorar; loadSimilarArtists maneja su propia visibilidad
            }

            android.util.Log.d("HomeFragment", "Carga de datos completada")

        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "Error cargando datos", e)
            showError("Error al cargar contenido: ${e.localizedMessage}")
            if (!isRefresh) {
                withContext(Dispatchers.Main) { showLoading(false) }
            }
            throw e
        }
    }

    private fun loadData() {
        // Verificar si el repositorio está configurado
        if (!musicRepository.isConfigured()) {
            // Importante: ocultar overlay de loading para permitir interacción (e.g., ir a Configuración)
            showLoading(false)
            showError("Configura tu servidor Navidrome para ver contenido")
            return
        }

        // Asegurar que el cache se resetee si cambió server/usuario
        musicRepository.ensureCacheScope()

        // DEBUG: Agregar logs para diagnóstico
        android.util.Log.d("HomeFragment", "Starting data load...")
        android.util.Log.d("HomeFragment", "Server URL: ${musicRepository.serverUrl}")
        android.util.Log.d("HomeFragment", "Username: ${musicRepository.username}")

        // IMPORTANTE: Reinicializar NavidromeClient con la URL guardada
        try {
            musicRepository.serverUrl?.let { serverUrl ->
                com.arantec.castafiore.data.network.NavidromeClient.initialize(serverUrl)
                android.util.Log.d("HomeFragment", "NavidromeClient initialized with: $serverUrl")
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeFragment", "Error initializing NavidromeClient: ${e.message}")
            // Importante: ocultar overlay de loading si hay error de configuración
            showLoading(false)
            showError("Error de configuración del servidor")
            return
        }

        // Usar lifecycle del view para evitar fugas cuando se destruye la vista
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                loadDataInternal(isRefresh = false)
                isInitialLoad = false
                lastLoadTime = System.currentTimeMillis()
            } catch (_: Exception) {
                // Error ya manejado en loadDataInternal
            }
        }
    }

    private suspend fun loadRecentlyAddedAlbums() {
        try {
            val now = System.currentTimeMillis()
            val cacheFresh = (cachedRecentlyAdded != null && (now - cachedRecentlyAddedTime) < DISCOVER_TTL_MS)

            if (cacheFresh) {
                val unique = cachedRecentlyAdded!!.distinctBy { it.id }
                if (unique.isNotEmpty()) {
                    withContext(Dispatchers.Main) { recentlyAddedAdapter.updateAlbums(unique.take(10)) }
                }
                return
            } else if (cachedRecentlyAdded != null && recentlyAddedAdapter.itemCount == 0) {
                // Cache expirada: mostrarla para evitar parpadeo y luego refrescar
                val unique = cachedRecentlyAdded!!.distinctBy { it.id }
                if (unique.isNotEmpty()) {
                    withContext(Dispatchers.Main) { recentlyAddedAdapter.updateAlbums(unique.take(10)) }
                }
            }

            musicRepository.getNewestAlbums().fold(
                onSuccess = { albums ->
                    val unique = albums.distinctBy { it.id }
                    if (unique.isNotEmpty()) {
                        withContext(Dispatchers.Main) { recentlyAddedAdapter.updateAlbums(unique.take(10)) }
                        cachedRecentlyAdded = unique
                        cachedRecentlyAddedTime = System.currentTimeMillis()
                    } else {
                        // Si no hay álbumes nuevos únicos, intentar cargar álbumes generales
                        loadFallbackAlbums(recentlyAddedAdapter, "newest")
                    }
                },
                onFailure = {
                    loadFallbackAlbums(recentlyAddedAdapter, "newest")
                }
            )
        } catch (e: Exception) {
            android.util.Log.w("HomeFragment", "Fallo loadRecentlyAddedAlbums, conservando items existentes: ${e.message}")
            withContext(Dispatchers.Main) {
                // No borrar ítems existentes; sólo si no hay nada intentar mostrar cache expirada
                if (recentlyAddedAdapter.itemCount == 0) {
                    val unique = cachedRecentlyAdded?.distinctBy { it.id }?.take(10).orEmpty()
                    if (unique.isNotEmpty()) {
                        recentlyAddedAdapter.updateAlbums(unique)
                    }
                    // Si tampoco hay cache, dejamos vacío sin forzar a vacío explícitamente
                }
            }
        }
    }

    private suspend fun loadRecentlyPlayedAlbums() {
        try {
            musicRepository.getRecentlyPlayedAlbums().fold(
                onSuccess = { albums ->
                    val unique = albums.distinctBy { it.id }
                    if (unique.isNotEmpty()) {
                        withContext(Dispatchers.Main) { recentlyPlayedAdapter.updateAlbums(unique.take(10)) }
                    } else {
                        // Usuario sin historial: no usar fallback para evitar datos no relevantes
                        android.util.Log.d("HomeFragment", "RecentlyPlayed vacío para el usuario; se ocultará la sección")
                    }
                },
                onFailure = {
                    // Error al cargar historial: no usar fallback, mantener sección oculta si está vacía
                    android.util.Log.w("HomeFragment", "getRecentlyPlayedAlbums falló: ${it.message}")
                }
            )
        } catch (e: Exception) {
            android.util.Log.w("HomeFragment", "Fallo loadRecentlyPlayedAlbums, conservando items existentes: ${e.message}")
            withContext(Dispatchers.Main) {
                // No borrar ítems existentes; si ya hay contenido, mantenerlo
                // Si está vacío, dejarlo vacío; no forzar a vacío
            }
        }
    }

    private suspend fun loadMostPlayedAlbums() {
        try {
            musicRepository.getMostPlayedAlbums().fold(
                onSuccess = { albums ->
                    val unique = albums.distinctBy { it.id }
                    if (unique.isNotEmpty()) {
                        withContext(Dispatchers.Main) { mostPlayedAdapter.updateAlbums(unique.take(10)) }
                    } else {
                        // Usuario sin datos de "más reproducidos": no usar fallback
                        android.util.Log.d("HomeFragment", "MostPlayed vacío para el usuario; se ocultará la sección")
                    }
                },
                onFailure = {
                    // Error al cargar: no usar fallback
                    android.util.Log.w("HomeFragment", "getMostPlayedAlbums falló: ${it.message}")
                }
            )
        } catch (e: Exception) {
            android.util.Log.w("HomeFragment", "Fallo loadMostPlayedAlbums, conservando items existentes: ${e.message}")
            withContext(Dispatchers.Main) {
                // No borrar ítems existentes; si ya hay contenido, mantenerlo
                // Si está vacío, dejarlo vacío; no forzar a vacío
            }
        }
    }

    private suspend fun loadFallbackAlbums(adapter: AlbumHorizontalAdapter, type: String) {
        try {
            // Como fallback, cargar álbumes generales
            musicRepository.getAlbums().fold(
                onSuccess = { albums ->
                    val unique = albums.distinctBy { it.id }
                    val limitedAlbums = when (type) {
                        "newest" -> unique.take(10)
                        "recent" -> unique.shuffled().take(10)
                        "frequent" -> unique.sortedByDescending { it.playCount ?: 0 }.take(10)
                        else -> unique.take(10)
                    }
                    withContext(Dispatchers.Main) {
                        if (limitedAlbums.isNotEmpty() || adapter.itemCount == 0) {
                            adapter.updateAlbums(limitedAlbums)
                        } else {
                            android.util.Log.d("HomeFragment", "Fallback ($type) vacío; se conserva la lista existente (${adapter.itemCount})")
                        }
                    }
                    // Cachear fallback para "newest" también
                    if (type == "newest" && unique.isNotEmpty()) {
                        cachedRecentlyAdded = unique
                        cachedRecentlyAddedTime = System.currentTimeMillis()
                    }
                },
                onFailure = { err ->
                    android.util.Log.w("HomeFragment", "Fallback getAlbums falló ($type): ${err.message}")
                    withContext(Dispatchers.Main) {
                        // No limpiar si ya hay contenido; mantener UI estable
                        if (adapter.itemCount == 0) {
                            android.util.Log.d("HomeFragment", "Sin contenido de fallback ($type); se mantiene UI sin cambios")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            android.util.Log.w("HomeFragment", "Excepción en loadFallbackAlbums ($type): ${e.message}")
            withContext(Dispatchers.Main) {
                // No limpiar si ya hay contenido; mantener UI estable
                if (adapter.itemCount == 0) {
                    android.util.Log.d("HomeFragment", "Excepción con lista vacía ($type); se mantiene UI sin cambios")
                }
            }
        }
    }

    private suspend fun loadSimilarArtists() {
        try {
            // Base artist should come from Navidrome "recently played" API, not local player state
            val recentAlbumsResult = musicRepository.getRecentlyPlayedAlbums()
            recentAlbumsResult.fold(
                onSuccess = { recentAlbums ->
                    val first = recentAlbums.firstOrNull()
                    val artistId = first?.artistId?.takeIf { it.isNotBlank() }
                    val artistName = first?.artist?.takeIf { it.isNotBlank() }

                    if (artistId.isNullOrEmpty() || artistName.isNullOrEmpty()) {
                        binding.similarHeaderContainer.visibility = View.GONE
                        binding.rvSimilarArtists.visibility = View.GONE
                        return
                    }

                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = musicRepository.getSimilarArtists(artistId)
                        result.fold(
                            onSuccess = { artists ->
                                val list = artists.filter { it.id != artistId }.distinctBy { it.id }.take(10)
                                if (list.isNotEmpty()) {
                                    similarArtistsAdapter.submit(list)
                                    // Header con artista base
                                    binding.tvSimilarArtistName.apply {
                                        text = artistName
                                        isClickable = true
                                        setOnClickListener {
                                            val bundle = android.os.Bundle().apply {
                                                putString("artistId", artistId)
                                                putString("artistName", artistName)
                                            }
                                            findNavController().navigate(R.id.artistDetailFragment, bundle)
                                        }
                                    }
                                    try {
                                        val server = musicRepository.serverUrl
                                        if (!server.isNullOrEmpty()) {
                                            val (u, t, s) = musicRepository.getAuthParams()
                                            val url = ImageLoader.buildArtistImageUrl(server, artistId, u, t, s, 300)
                                            ImageLoader.loadArtistImage(requireContext(), binding.ivSimilarArtist, url)
                                        } else {
                                            binding.ivSimilarArtist.setImageResource(R.drawable.ic_person)
                                        }
                                    } catch (_: Exception) {
                                        binding.ivSimilarArtist.setImageResource(R.drawable.ic_person)
                                    }
                                    binding.similarHeaderContainer.visibility = View.VISIBLE
                                    binding.rvSimilarArtists.visibility = View.VISIBLE
                                } else {
                                    binding.similarHeaderContainer.visibility = View.GONE
                                    binding.rvSimilarArtists.visibility = View.GONE
                                }
                            },
                            onFailure = {
                                binding.similarHeaderContainer.visibility = View.GONE
                                binding.rvSimilarArtists.visibility = View.GONE
                            }
                        )
                    }
                },
                onFailure = {
                    binding.similarHeaderContainer.visibility = View.GONE
                    binding.rvSimilarArtists.visibility = View.GONE
                }
            )
        } catch (_: Exception) {
            binding.similarHeaderContainer.visibility = View.GONE
            binding.rvSimilarArtists.visibility = View.GONE
        }
    }

    private suspend fun loadFavoriteArtists() {
        try {
            musicRepository.getStarredArtists().fold(
                onSuccess = { artists ->
                    val list = artists.distinctBy { it.id }.take(10)
                    // Actualizar UI de forma síncrona para evitar parpadeos
                    withContext(Dispatchers.Main) {
                        if (list.isNotEmpty()) {
                            favoriteArtistsAdapter.submit(list)
                            binding.tvFavoriteArtistsTitle.visibility = View.VISIBLE
                            binding.rvFavoriteArtists.visibility = View.VISIBLE
                        } else {
                            binding.tvFavoriteArtistsTitle.visibility = View.GONE
                            binding.rvFavoriteArtists.visibility = View.GONE
                            // Si en la primera carga viene vacío, intentar una vez más tras una breve espera
                            if (isInitialLoad && !attemptedFavoriteRetry) {
                                attemptedFavoriteRetry = true
                                viewLifecycleOwner.lifecycleScope.launch {
                                    kotlinx.coroutines.delay(800)
                                    try { loadFavoriteArtists() } catch (_: Exception) { }
                                }
                            }
                        }
                    }
                },
                onFailure = {
                    withContext(Dispatchers.Main) {
                        binding.tvFavoriteArtistsTitle.visibility = View.GONE
                        binding.rvFavoriteArtists.visibility = View.GONE
                        if (isInitialLoad && !attemptedFavoriteRetry) {
                            attemptedFavoriteRetry = true
                            viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(1000)
                                try { loadFavoriteArtists() } catch (_: Exception) { }
                            }
                        }
                    }
                }
            )
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                binding.tvFavoriteArtistsTitle.visibility = View.GONE
                binding.rvFavoriteArtists.visibility = View.GONE
                if (isInitialLoad && !attemptedFavoriteRetry) {
                    attemptedFavoriteRetry = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(1000)
                        try { loadFavoriteArtists() } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    private fun onAlbumClick(album: Album) {
        // Por ahora, navegar usando Bundle hasta que se generen las clases de Navigation
        val bundle = Bundle().apply {
            putParcelable("album", album)
        }
        findNavController().navigate(R.id.albumDetailFragment, bundle)
    }

    private fun onArtistClick(artist: com.arantec.castafiore.data.models.Artist) {
        val bundle = Bundle().apply {
            putString("artistId", artist.id)
            putString("artistName", artist.name)
        }
        findNavController().navigate(R.id.artistDetailFragment, bundle)
    }

    private var isLoading: Boolean = false
    private fun showLoading(show: Boolean) {
        isLoading = show

        // Usar únicamente el ProgressBar local para no bloquear la app
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE

        if (show) {
            // Durante loading, reservar espacio para la sección superior (favoritos)
            binding.tvFavoriteArtistsTitle.visibility = View.INVISIBLE
            binding.rvFavoriteArtists.visibility = View.INVISIBLE

            // No mostrar "Agregados recientemente" aún para no dar sensación de carga desde el medio
            binding.tvRecentlyAddedTitle.visibility = View.INVISIBLE
            binding.rvRecentlyAdded.visibility = View.INVISIBLE

            // Reservar espacio para secciones opcionales
            binding.tvRecentlyPlayedTitle.visibility = View.INVISIBLE
            binding.rvRecentlyPlayed.visibility = View.INVISIBLE
            binding.tvMostPlayedTitle.visibility = View.INVISIBLE
            binding.rvMostPlayed.visibility = View.INVISIBLE

            // Similar artists ocultos durante loading
            binding.similarHeaderContainer.visibility = View.GONE
            binding.rvSimilarArtists.visibility = View.GONE
        } else {
            // Mostrar secciones disponibles según contenido cargado
            if (this::favoriteArtistsAdapter.isInitialized && favoriteArtistsAdapter.itemCount > 0) {
                binding.tvFavoriteArtistsTitle.visibility = View.VISIBLE
                binding.rvFavoriteArtists.visibility = View.VISIBLE
            }
            binding.tvRecentlyAddedTitle.visibility = View.VISIBLE
            binding.rvRecentlyAdded.visibility = View.VISIBLE
            updateOptionalSectionsVisibility()
        }
    }

    private fun updateOptionalSectionsVisibility() {
        if (isLoading) {
            // No modificar visibilidades durante loading para preservar espacio (se mantienen INVISIBLE)
            return
        }

        val hasRecent = recentlyPlayedAdapter.itemCount > 0
        val hasMost = mostPlayedAdapter.itemCount > 0

        binding.tvRecentlyPlayedTitle.visibility = if (hasRecent) View.VISIBLE else View.GONE
        binding.rvRecentlyPlayed.visibility = if (hasRecent) View.VISIBLE else View.GONE

        binding.tvMostPlayedTitle.visibility = if (hasMost) View.VISIBLE else View.GONE
        binding.rvMostPlayed.visibility = if (hasMost) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
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

        // Asegurar que las secciones base estén visibles al volver
        ensureBaseSectionsVisible()

        // Si por alguna razón la lista quedó vacía, intentar rellenar con cache expirada para evitar UI en blanco
        if (this::recentlyAddedAdapter.isInitialized && recentlyAddedAdapter.itemCount == 0) {
            applyCachedRecentlyAddedIfAvailable(allowExpired = true)
        }

        // Auto-refresh si los datos están viejos o las listas están muy vacías tras inactividad
        val shouldRefreshByTime = appLifecycleManager.shouldRefreshBasedOnTime()
        val tooSparse = (
            (this::recentlyAddedAdapter.isInitialized && recentlyAddedAdapter.itemCount <= 1) &&
            (this::recentlyPlayedAdapter.isInitialized && recentlyPlayedAdapter.itemCount <= 1) &&
            (this::mostPlayedAdapter.isInitialized && mostPlayedAdapter.itemCount <= 1)
        )
        if (shouldRefreshByTime || tooSparse) {
            performRefresh(isAutoRefresh = true, forceRefresh = true)
        } else {
            // Recalcular visibilidad de secciones opcionales por si los observers no disparan
            updateOptionalSectionsVisibility()
        }
    }

    private fun ensureBaseSectionsVisible() {
        if (isLoading) {
            // No forzar visibilidad de favoritos aquí; dejar que loadFavoriteArtists controle su visibilidad
            binding.tvRecentlyAddedTitle.visibility = View.INVISIBLE
            binding.rvRecentlyAdded.visibility = View.INVISIBLE
        } else {
            // Si favoritos ya está listo, mantenerlo visible
            if (this::favoriteArtistsAdapter.isInitialized && favoriteArtistsAdapter.itemCount > 0) {
                binding.tvFavoriteArtistsTitle.visibility = View.VISIBLE
                binding.rvFavoriteArtists.visibility = View.VISIBLE
            }
            binding.tvRecentlyAddedTitle.visibility = View.VISIBLE
            binding.rvRecentlyAdded.visibility = View.VISIBLE
        }
        // Removed: tvDiscoverTitle/rvDiscover
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

        // Asegurar que cualquier overlay global que haya quedado se oculte
        try { loadingHost?.showGlobalLoading(false) } catch (_: Exception) {}

        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        loadingHost = null
    }

    override fun hasContent(): Boolean {
        val hasAny = (
            (this::recentlyAddedAdapter.isInitialized && recentlyAddedAdapter.itemCount > 0) ||
            (this::recentlyPlayedAdapter.isInitialized && recentlyPlayedAdapter.itemCount > 0) ||
            (this::mostPlayedAdapter.isInitialized && mostPlayedAdapter.itemCount > 0) ||
            (this::favoriteArtistsAdapter.isInitialized && favoriteArtistsAdapter.itemCount > 0)
        )
        return hasAny
    }

    private fun applyCachedRecentlyAddedIfAvailable(allowExpired: Boolean = false) {
        val now = System.currentTimeMillis()
        val hasAdapterItems = this::recentlyAddedAdapter.isInitialized && recentlyAddedAdapter.itemCount > 0
        val cacheExists = cachedRecentlyAdded != null && cachedRecentlyAdded!!.isNotEmpty()
        val cacheFresh = (cachedRecentlyAdded != null && (now - cachedRecentlyAddedTime) < DISCOVER_TTL_MS)

        // Mostrar cache fresca siempre; si allowExpired=true y no hay items en el adapter, mostrar aunque esté expirada
        if ((cacheFresh || (allowExpired && !hasAdapterItems)) && cacheExists) {
            val unique = cachedRecentlyAdded!!.distinctBy { it.id }
            if (unique.isNotEmpty()) {
                recentlyAddedAdapter.updateAlbums(unique.take(10))
                ensureBaseSectionsVisible()
                updateOptionalSectionsVisibility()
            }
        }
    }

    companion object {
        // Cache en memoria para Recently Added reutiliza el mismo TTL
        private var cachedRecentlyAdded: List<Album>? = null
        private var cachedRecentlyAddedTime: Long = 0L
        private const val DISCOVER_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }
}
