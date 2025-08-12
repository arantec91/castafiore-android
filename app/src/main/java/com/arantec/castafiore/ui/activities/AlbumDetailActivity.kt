package com.arantec.castafiore.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Album
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ActivityAlbumDetailBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.SongAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.launch

class AlbumDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumDetailBinding
    private lateinit var musicRepository: MusicRepository
    private lateinit var songAdapter: SongAdapter
    private var musicService: MusicService? = null
    private var isBound = false
    private var currentAlbum: Album? = null
    private var albumSongs = mutableListOf<Song>()

    companion object {
        const val EXTRA_ALBUM = "extra_album"

        fun createIntent(context: Context, album: Album): Intent {
            return Intent(context, AlbumDetailActivity::class.java).apply {
                putExtra(EXTRA_ALBUM, album)
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        musicRepository = MusicRepository.getInstance(this)

        // Obtener álbum de los extras
        currentAlbum = intent.getParcelableExtra(EXTRA_ALBUM)

        if (currentAlbum == null) {
            finish()
            return
        }

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        loadAlbumDetails()
    }

    private fun setupUI() {
        // Ocultar ActionBar completamente
        supportActionBar?.hide()

        // Ocultar toolbar
        binding.toolbar.visibility = android.view.View.GONE

        // Configurar toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Configurar bottom navigation
        setupBottomNavigation()

        // Mostrar información básica del álbum
        currentAlbum?.let { album ->
            binding.tvAlbumTitle.text = album.name
            binding.tvArtistName.text = album.artist

            // Formatear información del álbum
            val year = album.year?.toString() ?: "Desconocido"
            val songCount = album.songCount
            val duration = formatDuration(album.duration)
            binding.tvAlbumInfo.text = "Álbum • $year • $songCount canciones, ${formatAlbumDuration(album.duration)}"

            // Cargar imagen del álbum
            loadAlbumCover(album)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // Volver a MainActivity con Home seleccionado
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("selected_tab", "home")
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                    true
                }
                R.id.nav_search -> {
                    // Volver a MainActivity con Search seleccionado
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("selected_tab", "search")
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                    true
                }
                R.id.nav_library -> {
                    // Volver a MainActivity con Library seleccionado
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("selected_tab", "library")
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }
        
        // No marcar ningún item como seleccionado
        binding.bottomNavigation.menu.setGroupCheckable(0, false, true)
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(
            onSongClick = { song, position ->
                playSongFromAlbum(song, position)
            },
            onSongMoreClick = { song ->
                showSongOptions(song)
            }
        )

        binding.rvSongs.apply {
            layoutManager = LinearLayoutManager(this@AlbumDetailActivity)
            adapter = songAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupClickListeners() {
        // Botón play principal
        binding.fabPlay.setOnClickListener {
            playAlbum()
        }

        // Botón shuffle
        binding.btnShuffle.setOnClickListener {
            playAlbumShuffle()
        }

        // Botón más opciones
        binding.btnMore.setOnClickListener {
            showAlbumOptions()
        }
    }

    private fun loadAlbumDetails() {
        currentAlbum?.let { album ->
            lifecycleScope.launch {
                try {
                    musicRepository.getAlbumDetail(album.id).fold(
                        onSuccess = { detailedAlbum ->
                            // Usar las canciones reales del álbum si están disponibles
                            val songsToUse = detailedAlbum.songs
                            if (!songsToUse.isNullOrEmpty()) {
                                albumSongs.clear()
                                albumSongs.addAll(songsToUse)
                                songAdapter.updateSongs(albumSongs)
                            } else {
                                // Si no hay canciones en la respuesta, generar de muestra
                                generateSampleSongs(album)
                            }
                        },
                        onFailure = {
                            // Si falla, generar canciones de muestra
                            generateSampleSongs(album)
                        }
                    )
                } catch (e: Exception) {
                    generateSampleSongs(album)
                }
            }
        }
    }

    private fun generateSampleSongs(album: Album) {
        // Temporal: generar canciones de muestra solo si no tenemos canciones reales
        val sampleSongs = mutableListOf<Song>()

        // Generar títulos más realistas basados en el álbum
        val sampleTitles = listOf(
            "Intro", "First Light", "Dreams", "Midnight", "Echoes", "Journey",
            "Memories", "Dawn", "Whispers", "Finale", "Outro", "Reflection",
            "Hope", "Freedom", "Paradise", "Thunder", "Ocean", "Mountain"
        )

        for (i in 1..album.songCount) {
            val titleIndex = (i - 1) % sampleTitles.size
            val title = if (i <= sampleTitles.size) {
                sampleTitles[titleIndex]
            } else {
                "${sampleTitles[titleIndex]} (Part ${(i - 1) / sampleTitles.size + 1})"
            }

            sampleSongs.add(
                Song(
                    id = "${album.id}_$i",
                    title = title,
                    artist = album.artist,
                    album = album.name,
                    duration = (180 + (Math.random() * 120)).toInt(), // 3-5 min
                    track = i,
                    albumId = album.id,
                    artistId = album.artistId
                )
            )
        }

        albumSongs.clear()
        albumSongs.addAll(sampleSongs)
        songAdapter.updateSongs(albumSongs)
    }

    private fun loadAlbumCover(album: Album) {
        val requestOptions = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .error(R.drawable.ic_album_placeholder)
            .placeholder(R.drawable.ic_album_placeholder)

        if (album.coverArt != null && musicRepository.serverUrl != null) {
            try {
                val (username, token, salt) = musicRepository.getAuthParams()
                val coverUrl = album.getCoverArtUrl(
                    musicRepository.serverUrl!!,
                    username,
                    token,
                    salt
                )

                Glide.with(this)
                    .load(coverUrl)
                    .apply(requestOptions)
                    .into(binding.ivAlbumCoverLarge)
            } catch (e: Exception) {
                Glide.with(this)
                    .load(R.drawable.ic_album_placeholder)
                    .apply(requestOptions)
                    .into(binding.ivAlbumCoverLarge)
            }
        } else {
            Glide.with(this)
                .load(R.drawable.ic_album_placeholder)
                .apply(requestOptions)
                .into(binding.ivAlbumCoverLarge)
        }
    }

    private fun playAlbum() {
        if (albumSongs.isNotEmpty()) {
            musicService?.playQueue(albumSongs, 0)
        }
    }

    private fun playAlbumShuffle() {
        if (albumSongs.isNotEmpty()) {
            val shuffledSongs = albumSongs.shuffled()
            musicService?.playQueue(shuffledSongs, 0)
        }
    }

    private fun playSongFromAlbum(song: Song, position: Int) {
        musicService?.playQueue(albumSongs, position)
    }

    private fun showSongOptions(song: Song) {
        // TODO: Implementar menú de opciones para la canción
    }

    private fun showAlbumOptions() {
        // TODO: Implementar menú de opciones para el álbum
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60

        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes} min"
        }
    }

    private fun formatAlbumDuration(seconds: Int): String {
        val minutes = seconds / 60
        val seconds = seconds % 60

        return if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MusicService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
