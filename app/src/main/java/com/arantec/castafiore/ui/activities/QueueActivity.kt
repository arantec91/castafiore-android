package com.arantec.castafiore.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ActivityQueueBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.ui.adapters.QueueAdapter
import com.arantec.castafiore.ui.helpers.QueueItemTouchHelperCallback
import com.arantec.castafiore.utils.StatusBarUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
import com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import com.arantec.castafiore.utils.snack
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.utils.NetworkUtils
import com.arantec.castafiore.data.download.SongDownloadManager
import java.io.File

class QueueActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQueueBinding
    private lateinit var musicRepository: MusicRepository
    private lateinit var queueAdapter: QueueAdapter
    private var musicService: MusicService? = null
    private var isBound = false
    private var itemTouchHelper: ItemTouchHelper? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            setupMusicServiceListeners()
            updateQueue()
            updateCurrentSong()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // CRITICAL: Force disable Android 14's automatic edge-to-edge behavior
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            // Must be done before setContentView
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

            val darkColor = ContextCompat.getColor(this, R.color.background_primary)
            window.statusBarColor = darkColor
            window.navigationBarColor = darkColor

            // Force disable edge-to-edge - critical for API 36
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }

        binding = ActivityQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply additional fixes after view creation
        StatusBarUtils.setStatusBarColor(this)

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            // Post-creation enforcement
            binding.root.post {
                finalNavigationBarEnforcement()
            }
        }

        applyWindowInsets()
        musicRepository = MusicRepository.getInstance(this)
        setupViews()
        setupRecyclerView()
        bindMusicService()
    }

    private fun finalNavigationBarEnforcement() {
        try {
            val darkColor = ContextCompat.getColor(this, R.color.background_primary)

            // Triple enforcement for stubborn API 36
            window.navigationBarColor = darkColor
            window.statusBarColor = darkColor

            // Force window insets controller to behave
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    // Clear light appearance flags to force dark bars
                    controller.setSystemBarsAppearance(
                        0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )

                    // Force stable behavior
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_DEFAULT
                }
            }

            // Force decorView background
            window.decorView.setBackgroundColor(darkColor)

        } catch (e: Exception) {
            android.util.Log.e("QueueActivity", "Error in finalNavigationBarEnforcement", e)
        }
    }

    private fun applyWindowInsets() {
        // For API 36, use a different approach to handle window insets
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            // Simplified insets handling for Android 14+
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                // Apply top inset to the root to clear status bar
                v.setPadding(0, systemBars.top, 0, 0)

                // Apply bottom inset directly to the RecyclerView
                binding.recyclerViewQueue.setPadding(0, 0, 0, systemBars.bottom)

                WindowInsetsCompat.CONSUMED
            }
        } else {
            // Original logic for older versions
            val baseRootPaddingLeft = binding.root.paddingLeft
            val baseRootPaddingTop = binding.root.paddingTop
            val baseRootPaddingRight = binding.root.paddingRight
            val baseRootPaddingBottom = binding.root.paddingBottom

            val baseRecyclerPaddingLeft = binding.recyclerViewQueue.paddingLeft
            val baseRecyclerPaddingTop = binding.recyclerViewQueue.paddingTop
            val baseRecyclerPaddingRight = binding.recyclerViewQueue.paddingRight
            val baseRecyclerPaddingBottom = binding.recyclerViewQueue.paddingBottom

            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(
                    baseRootPaddingLeft,
                    baseRootPaddingTop + systemBars.top,
                    baseRootPaddingRight,
                    baseRootPaddingBottom
                )
                binding.recyclerViewQueue.setPadding(
                    baseRecyclerPaddingLeft,
                    baseRecyclerPaddingTop,
                    baseRecyclerPaddingRight,
                    baseRecyclerPaddingBottom + systemBars.bottom
                )
                insets
            }
        }

        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupViews() {
        // Botón cerrar
        binding.btnClose.setOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        queueAdapter = QueueAdapter(
            musicRepository = musicRepository,
            onSongClick = { song, position ->
                // Calcular la posición real en la cola completa
                val realPosition = getCurrentIndex() + 1 + position
                musicService?.playFromQueue(realPosition)
                finish() // Cerrar la cola y volver al player
            },
            onRemoveSong = { song, position ->
                // Calcular la posición real en la cola completa
                val realPosition = getCurrentIndex() + 1 + position
                musicService?.removeFromQueue(realPosition)
                updateQueueCount()
                checkEmptyState()
            },
            onMoveSong = { fromPosition, toPosition ->
                // Calcular las posiciones reales en la cola completa
                val currentIndex = getCurrentIndex()
                val realFromPosition = currentIndex + 1 + fromPosition
                val realToPosition = currentIndex + 1 + toPosition
                musicService?.moveInQueue(realFromPosition, realToPosition)
            },
            onStartDrag = { viewHolder ->
                // Iniciar arrastre
                itemTouchHelper?.startDrag(viewHolder)
            },
            onSongLongPress = { song, _ ->
                showSongOptions(song)
            }
        )

        binding.recyclerViewQueue.apply {
            layoutManager = LinearLayoutManager(this@QueueActivity)
            adapter = queueAdapter
        }

        // Configurar ItemTouchHelper para drag & drop
        val callback = QueueItemTouchHelperCallback(queueAdapter)
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(binding.recyclerViewQueue)
    }

    private fun getCurrentIndex(): Int {
        return musicService?.getCurrentIndex() ?: 0
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindMusicService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            musicService = null
        }
    }

    private fun setupMusicServiceListeners() {
        musicService?.let { service ->
            // Listener para cambios de canción
            service.addSongChangeListener { song: Song? ->
                runOnUiThread {
                    updateCurrentSongDisplay(song)
                }
            }
        }
    }

    private fun updateQueue() {
        musicService?.let { service ->
            val queue = service.getQueue()
            val currentIndex = service.getCurrentIndex()

            // Mostrar solo las canciones que vienen después de la actual
            val upcomingSongs: List<Song> = if (currentIndex < queue.size - 1) {
                queue.subList(currentIndex + 1, queue.size)
            } else {
                emptyList()
            }

            updateQueueDisplay(upcomingSongs)
        }
    }

    private fun updateQueueDisplay(queue: List<Song>) {
        queueAdapter.updateSongs(queue)
        updateQueueCount()
        checkEmptyState()
    }

    private fun updateCurrentSong() {
        musicService?.getCurrentSong()?.let { song ->
            updateCurrentSongDisplay(song)
        }
    }

    private fun updateCurrentSongDisplay(song: Song?) {
        if (song != null) {
            binding.tvCurrentSongTitle.text = song.title
            binding.tvCurrentArtist.text = song.artist
            loadCurrentAlbumArt(song)
            binding.layoutNowPlaying.visibility = View.VISIBLE
        } else {
            binding.layoutNowPlaying.visibility = View.GONE
        }
    }

    private fun loadCurrentAlbumArt(song: Song) {
        try {
            val (username, token, salt) = musicRepository.getAuthParams()
            val server = musicRepository.serverUrl
            val coverId = song.coverArt ?: song.albumId
            val coverUrl = if (!server.isNullOrEmpty() && !coverId.isNullOrEmpty()) {
                ImageLoader.buildCoverArtUrl(server, coverId, username, token, salt, 200)
            } else null

            val context = this
            val dm = SongDownloadManager.getInstance(context)

            // Check if song audio is downloaded (covers are usually downloaded with songs)
            val isDownloaded = dm.isSongDownloaded(song.id)
            val localCoverFile = if (isDownloaded) {
                // Try to find a cover file in the downloads directory
                val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
                File(downloadsDir, "${song.id}_cover.jpg")
            } else null

            // 1) Portada local si existe
            if (localCoverFile?.exists() == true) {
                Glide.with(context)
                    .load(localCoverFile)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(binding.ivCurrentAlbumArt)
                return
            }

            // 2) Sin conexión: intentar desde caché
            if (!NetworkUtils.isNetworkAvailable(context) && coverUrl != null) {
                Glide.with(context)
                    .load(coverUrl)
                    .apply(RequestOptions().onlyRetrieveFromCache(true))
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(binding.ivCurrentAlbumArt)
                return
            }

            // 3) Carga normal desde URL
            if (coverUrl != null) {
                Glide.with(context)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(binding.ivCurrentAlbumArt)
            } else {
                binding.ivCurrentAlbumArt.setImageResource(R.drawable.ic_album_placeholder)
            }
        } catch (_: Exception) {
            binding.ivCurrentAlbumArt.setImageResource(R.drawable.ic_album_placeholder)
        }
    }

    private fun updateQueueCount() {
        val count = queueAdapter.itemCount
        binding.tvQueueCount.text = if (count == 1) {
            "1 canción"
        } else {
            "$count canciones"
        }
    }

    private fun checkEmptyState() {
        val isEmpty = queueAdapter.itemCount == 0
        binding.recyclerViewQueue.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.layoutEmptyQueue.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    // --- Song options from long press ---
    private fun showSongOptions(song: Song) {
        val bottomSheet = SongOptionsBottomSheet.newInstance(song, false)
            // Ya está en la cola; ocultar acciones redundantes
            .hideAddToQueueOption()
            .hidePlayNextOption()
            .setOnAddToPlaylistClickListener { s ->
                val selector = PlaylistSelectorBottomSheet.newInstance(s)
                selector.show(supportFragmentManager, "PlaylistSelectorBottomSheet")
            }
            .setOnViewAlbumClickListener { s ->
                navigateToAlbum(s)
            }
            .setOnViewArtistClickListener { s ->
                navigateToArtist(s)
            }
            .setOnShareClickListener { s ->
                shareSong(s)
            }
            .setOnSongInfoClickListener { s ->
                showSongInfoDialog(s)
            }

        bottomSheet.show(supportFragmentManager, "SongOptionsBottomSheet")
    }

    private fun shareSong(song: Song) {
        val shareText = "Escuchando: ${song.title} - ${song.artist}"
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        val chooser = Intent.createChooser(shareIntent, "Compartir canción")
        startActivity(chooser)
    }

    private fun navigateToAlbum(song: Song) {
        val albumId = song.albumId
        val albumName = song.album
        if (albumId != null) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_to", "album_detail")
                putExtra("album_id", albumId)
                putExtra("album_name", albumName)
                putExtra("artist_name", song.artist)
                putExtra("artist_id", song.artistId)
            }
            startActivity(intent)
            finish()
        } else {
            snack("Información del álbum no disponible")
        }
    }

    private fun navigateToArtist(song: Song) {
        val artistId = song.artistId
        val artistName = song.artist
        if (artistId != null) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_to", "artist_detail")
                putExtra("artist_id", artistId)
                putExtra("artist_name", artistName)
            }
            startActivity(intent)
            finish()
        } else {
            snack("Información del artista no disponible")
        }
    }

    private fun showSongInfoDialog(song: Song) {
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_song_info, null)

        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_title)
        val tvArtist = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_artist)
        val tvAlbum = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_album)
        val tvDuration = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_duration)
        val tvGenre = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_genre)
        val tvYear = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_year)
        val tvBitrate = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_bitrate)
        val tvFormat = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_format)
        val tvFileSize = dialogView.findViewById<android.widget.TextView>(R.id.tv_info_file_size)
        val ivCover = dialogView.findViewById<android.widget.ImageView>(R.id.iv_info_cover)

        tvTitle.text = song.title
        tvArtist.text = song.artist
        tvAlbum.text = song.album
        tvDuration.text = formatTime(song.duration.toLong())
        tvGenre.text = song.genre ?: "Género desconocido"
        tvYear.text = song.year?.toString() ?: "Año desconocido"
        tvBitrate.text = if (song.bitRate != null) "${song.bitRate} kbps" else "Bitrate desconocido"
        tvFormat.text = song.suffix?.uppercase() ?: "Formato desconocido"
        tvFileSize.text = if (song.size != null) {
            val sizeInMB = song.size / (1024.0 * 1024.0)
            String.format(java.util.Locale.getDefault(), "%.1f MB", sizeInMB)
        } else {
            "Tamaño desconocido"
        }

        try {
            val (username, token, salt) = musicRepository.getAuthParams()
            val server = musicRepository.serverUrl
            val coverId = song.coverArt ?: song.albumId
            val coverUrl = if (!server.isNullOrEmpty() && !coverId.isNullOrEmpty()) {
                ImageLoader.buildCoverArtUrl(server, coverId, username, token, salt, 300)
            } else null

            val dm = SongDownloadManager.getInstance(this)

            // Check if song audio is downloaded (covers are usually downloaded with songs)
            val isDownloaded = dm.isSongDownloaded(song.id)
            val localCoverFile = if (isDownloaded) {
                // Try to find a cover file in the downloads directory
                val downloadsDir = File(this.getExternalFilesDir(null), "downloads")
                File(downloadsDir, "${song.id}_cover.jpg")
            } else null

            // 1) Local cover
            if (localCoverFile?.exists() == true) {
                Glide.with(this)
                    .load(localCoverFile)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .into(ivCover)
            } else if (!NetworkUtils.isNetworkAvailable(this) && coverUrl != null) {
                // 2) Cache-only when offline
                Glide.with(this)
                    .load(coverUrl)
                    .apply(RequestOptions().onlyRetrieveFromCache(true))
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .into(ivCover)
            } else if (coverUrl != null) {
                // 3) Normal remote load
                Glide.with(this)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .into(ivCover)
            } else {
                ivCover.setImageResource(R.drawable.ic_album_placeholder)
            }
        } catch (_: Exception) {
            ivCover.setImageResource(R.drawable.ic_album_placeholder)
        }

        dialogBuilder.setView(dialogView)
            .setTitle("Información de la canción")
            .setPositiveButton("Cerrar") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    private fun formatTime(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, secs)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && android.os.Build.VERSION.SDK_INT >= 34) {
            // Re-enforce navigation bar settings when window gains focus for API 36
            finalNavigationBarEnforcement()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isBound) {
            bindMusicService()
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure consistent status and navigation bar colors
        StatusBarUtils.setStatusBarColor(this)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            finalNavigationBarEnforcement()
        }
    }

    override fun onStop() {
        super.onStop()
        unbindMusicService()
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindMusicService()
    }
}
