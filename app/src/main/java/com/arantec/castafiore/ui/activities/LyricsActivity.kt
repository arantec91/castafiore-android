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
import kotlin.math.abs
import kotlin.math.max

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
                        autoScrollEnabled = false
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        // End of any scroll (user or programmatic)
                        if (programmaticScrollInProgress) {
                            programmaticScrollInProgress = false
                        }
                        // User is no longer dragging; re-enable will be handled by progress crossing logic
                        userIsDragging = false
                    }
                    RecyclerView.SCROLL_STATE_SETTLING -> {
                        // Keep current flags; if settling resulted from user fling, autoScroll stays disabled
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // No-op: re-enable handled based on lyric progress crossing center during ticks
            }
        })

        // Auto-center active line only when auto-scroll is enabled
        adapter.onActiveIndexChanged = { index ->
            if (autoScrollEnabled && index >= 0) {
                smoothScrollActiveToCenterIfNeeded(index)
            }
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
            adapter.setLines(emptyList<com.arantec.castafiore.data.lyrics.LyricsLine>())
            autoScrollEnabled = false
            return
        }
        binding.tvStatus.apply { text = getString(R.string.searching_lyrics); visibility = android.view.View.VISIBLE }
        binding.progress.visibility = android.view.View.VISIBLE
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            val result = LyricsProvider.getInstance().getSyncedLyrics(song)
            if (result.isSuccess) {
                val lines = result.getOrNull().orEmpty()
                adapter.setLines(lines)
                binding.tvStatus.visibility = android.view.View.GONE
                // Enable auto-scroll on new lyrics and center current active line
                autoScrollEnabled = true
                val currentPos = musicService?.getCurrentPosition() ?: 0L
                adapter.updateProgress(currentPos)
                // If index didn't change callback yet (e.g., same line), force a center attempt
                val idx = adapter.getActiveIndex()
                if (idx >= 0) smoothScrollActiveToCenterIfNeeded(idx)
            } else {
                adapter.setLines(emptyList<com.arantec.castafiore.data.lyrics.LyricsLine>())
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
                // Re-enable auto-scroll if the active line's progress crosses the screen center
                if (!autoScrollEnabled && !userIsDragging) {
                    maybeReenableAutoScrollByProgressCrossing(pos)
                }
                handler.postDelayed(this, 120L)
            }
        }
        handler.postDelayed(progressRunnable!!, 100L)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun smoothScrollActiveToCenterIfNeeded(position: Int) {
        val lm = binding.rvLyrics.layoutManager as? LinearLayoutManager ?: return
        val targetView = lm.findViewByPosition(position)
        val centerY = recyclerCenterY()
        if (targetView != null) {
            val viewCenter = (targetView.top + targetView.bottom) / 2
            val delta = centerY - viewCenter
            if (abs(delta) <= dpToPx(6f)) return // already centered enough
        }
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                val viewCenter = viewStart + (viewEnd - viewStart) / 2
                val boxCenter = boxStart + (boxEnd - boxStart) / 2
                return boxCenter - viewCenter
            }
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
        }
        scroller.targetPosition = position
        programmaticScrollInProgress = true
        (binding.rvLyrics.layoutManager as? LinearLayoutManager)?.startSmoothScroll(scroller)
    }

    private fun maybeReenableAutoScrollByProgressCrossing(currentPosMs: Long) {
        val idx = adapter.getActiveIndex()
        if (idx < 0) return
        val line: LyricsLine = adapter.getLineAt(idx) ?: return
        val start = line.timeMs
        val duration = max(1L, line.durationMs)
        val p = ((currentPosMs - start).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val lm = binding.rvLyrics.layoutManager as? LinearLayoutManager ?: return
        val v = lm.findViewByPosition(idx) ?: return
        val center = recyclerCenterY()
        val spansCenter = v.top <= center && v.bottom >= center
        if (spansCenter && p >= 0.5f) {
            autoScrollEnabled = true
        }
    }

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
