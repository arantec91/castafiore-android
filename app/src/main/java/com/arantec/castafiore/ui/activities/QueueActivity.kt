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

        // Configurar status bar
        StatusBarUtils.setStatusBarColor(this)

        musicRepository = MusicRepository.getInstance(this)

        setupViews()
        setupRecyclerView()
        bindMusicService()
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

    override fun onStart() {
        super.onStart()
        if (!isBound) {
            bindMusicService()
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
