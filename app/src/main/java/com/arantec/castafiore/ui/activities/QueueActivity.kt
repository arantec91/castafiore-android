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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.arantec.castafiore.ui.dialogs.SongOptionsBottomSheet
import com.arantec.castafiore.ui.dialogs.PlaylistSelectorBottomSheet
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat

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
        binding = ActivityQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure consistent status and navigation bar colors
        StatusBarUtils.setStatusBarColor(this)
        enforceSolidNavigationBar()

        // Apply window insets so content does not overlap the status bar/navigation bar
        applyWindowInsets()

        musicRepository = MusicRepository.getInstance(this)

        setupViews()
        setupRecyclerView()
        bindMusicService()
    }

    private fun enforceSolidNavigationBar() {
        // Disable edge-to-edge for this activity
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // Set a solid dark navigation bar color and ensure light nav bar icons are disabled
        window.navigationBarColor = ContextCompat.getColor(this, R.color.dark_background)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val flags = window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun applyWindowInsets() {
        // Capture baseline paddings to avoid cumulative additions on re-applies
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
            // Apply top inset to the root so header clears the status bar
            v.setPadding(
                baseRootPaddingLeft,
                baseRootPaddingTop + systemBars.top,
                baseRootPaddingRight,
                baseRootPaddingBottom
            )
            // Apply bottom inset to the list so it clears the nav bar, preserving initial 16dp
            binding.recyclerViewQueue.setPadding(
                baseRecyclerPaddingLeft,
                baseRecyclerPaddingTop,
                baseRecyclerPaddingRight,
                baseRecyclerPaddingBottom + systemBars.bottom
            )
            insets
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
            val coverUrl = if (song.albumId != null) {
                "${musicRepository.serverUrl}/rest/getCoverArt.view?id=${song.albumId}&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=200"
            } else {
                null
            }

            if (coverUrl != null) {
                Glide.with(this)
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
            android.widget.Toast.makeText(this, "Información del álbum no disponible", android.widget.Toast.LENGTH_SHORT).show()
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
            android.widget.Toast.makeText(this, "Información del artista no disponible", android.widget.Toast.LENGTH_SHORT).show()
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
            val coverUrl = if (song.albumId != null) {
                "${musicRepository.serverUrl}/rest/getCoverArt.view?id=${song.albumId}&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=300"
            } else {
                null
            }

            if (coverUrl != null) {
                Glide.with(this)
                    .load(coverUrl)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(200))
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
        enforceSolidNavigationBar()
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
