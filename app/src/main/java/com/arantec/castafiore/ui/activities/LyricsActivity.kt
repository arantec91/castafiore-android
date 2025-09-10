package com.arantec.castafiore.ui.activities

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.data.lyrics.LyricsProvider
import com.arantec.castafiore.data.lyrics.LyricsLine
import com.arantec.castafiore.data.models.Song
import com.arantec.castafiore.databinding.ActivityLyricsBinding
import com.arantec.castafiore.service.MusicService
import com.arantec.castafiore.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

class LyricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLyricsBinding
    private var musicService: MusicService? = null
    private var isBound = false

    private val adapter = com.arantec.castafiore.ui.lyrics.LyricsAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private var loadJob: Job? = null
    private var songChangeListener: ((Song?) -> Unit)? = null

    // Auto-scroll state
    private var autoScrollEnabled = true
    private var userIsDragging = false
    private var programmaticScrollInProgress = false

    // Anchor scroll configuration (similar feeling to Spotify)
    private val anchorRatio = 0.40f // 40% desde la parte superior del área visible
    private val comfortZoneDp = 32f // zona muerta alrededor del ancla para evitar micro-ajustes

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            setupServiceListeners()
            updateFromService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply status bar insets to top padding (preserve existing 16dp)
        val initialTop = binding.contentContainer.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentContainer) { v, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, initialTop + status.top, v.paddingRight, v.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.contentContainer)

        // Back
        binding.btnBack.setOnClickListener { finish() }

        // Follow button
        binding.btnFollow.setOnClickListener {
            autoScrollEnabled = true
            binding.btnFollow.visibility = android.view.View.GONE
            val idx = adapter.getActiveIndex()
            if (idx >= 0) smoothScrollActiveToAnchor(idx)
        }

        // Recycler setup
        val lm = LinearLayoutManager(this)
        binding.rvLyrics.layoutManager = lm
        binding.rvLyrics.adapter = adapter
        // Attach scroll listener to manage auto-scroll state
        binding.rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        userIsDragging = true
                        if (autoScrollEnabled) {
                            autoScrollEnabled = false
                            showFollowButtonIfNeeded()
                        }
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (programmaticScrollInProgress) {
                            programmaticScrollInProgress = false
                        }
                        userIsDragging = false
                    }
                    RecyclerView.SCROLL_STATE_SETTLING -> { /* no-op */ }
                }
            }
        })

        // Auto-scroll anchored behavior
        adapter.onActiveIndexChanged = { index ->
            if (autoScrollEnabled && index >= 0) {
                ensureActiveWithinAnchorZone(index)
            }
        }

        // Seek to tapped lyric line time
        adapter.onLineClick = { index, line ->
            val pos = line.timeMs
            musicService?.seekTo(pos)
            adapter.updateProgress(pos)
            autoScrollEnabled = true
            if (binding.btnFollow.visibility == android.view.View.VISIBLE) binding.btnFollow.visibility = android.view.View.GONE
        }

        // Bind service
        bindMusicService()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        loadJob?.cancel()
        musicService?.let { svc ->
            songChangeListener?.let { svc.removeSongChangeListener(it) }
        }
        unbindMusicService()
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun unbindMusicService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun setupServiceListeners() {
        val listener: (Song?) -> Unit = { song ->
            runOnUiThread { loadLyricsFor(song) }
        }
        songChangeListener = listener
        musicService?.addSongChangeListener(listener)
    }

    private fun updateFromService() {
        val song = musicService?.getCurrentSong()
        // Update header
        updateHeader(song)
        loadLyricsFor(song)
        startProgressUpdates()
    }

    private fun updateHeader(song: Song?) {
        if (song == null) {
            binding.tvSongTitle.text = ""
            binding.tvArtistName.text = ""
        } else {
            binding.tvSongTitle.text = song.title
            binding.tvArtistName.text = song.artist
        }
    }

    private fun loadLyricsFor(song: Song?) {
        // Always refresh header when loading lyrics
        updateHeader(song)
        if (song == null) {
            binding.tvStatus.apply { text = getString(R.string.no_song); visibility = android.view.View.VISIBLE }
            adapter.setLines(emptyList<LyricsLine>())
            autoScrollEnabled = false
            return
        }
        // Hide status while loading; show only the spinner
        binding.tvStatus.visibility = android.view.View.GONE
        binding.progress.visibility = android.view.View.VISIBLE
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            val result = LyricsProvider.getInstance().getSyncedLyrics(applicationContext, song)
            if (result.isSuccess) {
                val lines = result.getOrNull().orEmpty()
                adapter.setLines(lines)
                binding.tvStatus.visibility = android.view.View.GONE
                autoScrollEnabled = true
                if (binding.btnFollow.visibility == android.view.View.VISIBLE) binding.btnFollow.visibility = android.view.View.GONE
                val currentPos = musicService?.getCurrentPosition() ?: 0L
                adapter.updateProgress(currentPos)
                val idx = adapter.getActiveIndex()
                if (idx >= 0) smoothScrollActiveToCenterIfNeeded(idx)
            } else {
                adapter.setLines(emptyList<LyricsLine>())
                binding.tvStatus.text = getString(R.string.no_lyrics)
                binding.tvStatus.visibility = android.view.View.VISIBLE
                autoScrollEnabled = false
            }
            binding.progress.visibility = android.view.View.GONE
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val pos = musicService?.getCurrentPosition() ?: 0L
                adapter.updateProgress(pos)
                // NO re-habilitamos auto-scroll automáticamente; el usuario controla con el botón "Seguir"
                handler.postDelayed(this, 120L)
            }
        }
        handler.postDelayed(progressRunnable!!, 100L)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    // Reemplaza el centrado con un anclaje estable
    private fun ensureActiveWithinAnchorZone(position: Int) {
        val lm = binding.rvLyrics.layoutManager as? LinearLayoutManager ?: return
        val rv = binding.rvLyrics
        val v = lm.findViewByPosition(position)
        val anchorY = anchorY()
        val comfort = dpToPx(comfortZoneDp)
        if (v != null) {
            val center = (v.top + v.bottom) / 2
            if (center in (anchorY - comfort)..(anchorY + comfort)) {
                return // dentro de la zona cómoda, no movemos
            }
        }
        smoothScrollActiveToAnchor(position)
    }

    private fun smoothScrollActiveToAnchor(position: Int) {
        val rv = binding.rvLyrics
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val anchorY = anchorY()
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                val viewCenter = viewStart + (viewEnd - viewStart) / 2
                // Ajustamos para ubicar el centro de la línea exactamente en anchorY relativo al RecyclerView
                val rvTop = 0 // coordenadas en el boxStart/boxEnd ya son relativas
                val desiredCenter = rvTop + anchorY
                return desiredCenter - viewCenter
            }
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
        }
        scroller.targetPosition = position
        programmaticScrollInProgress = true
        lm.startSmoothScroll(scroller)
    }

    private fun smoothScrollActiveToCenterIfNeeded(position: Int) { ensureActiveWithinAnchorZone(position) }

    private fun anchorY(): Int {
        val rv = binding.rvLyrics
        val contentTop = rv.paddingTop
        val contentBottom = rv.height - rv.paddingBottom
        val contentHeight = contentBottom - contentTop
        return (contentTop + contentHeight * anchorRatio).toInt()
    }

    private fun showFollowButtonIfNeeded() {
        if (binding.btnFollow.visibility != android.view.View.VISIBLE) {
            binding.btnFollow.alpha = 0f
            binding.btnFollow.visibility = android.view.View.VISIBLE
            binding.btnFollow.animate().alpha(1f).setDuration(150L).start()
        }
    }

    // Eliminamos maybeReenableAutoScrollByProgressCrossing (ya no se usa)

    private fun recyclerCenterY(): Int {
        val rv = binding.rvLyrics
        val contentTop = rv.paddingTop
        val contentBottom = rv.height - rv.paddingBottom
        return contentTop + (contentBottom - contentTop) / 2
    }

    private fun dpToPx(dp: Float): Int {
        val metrics = resources.displayMetrics
        return (dp * metrics.density + 0.5f).toInt()
    }
}
