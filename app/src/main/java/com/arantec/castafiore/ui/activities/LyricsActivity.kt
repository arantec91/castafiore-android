package com.arantec.castafiore.ui.activities

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

import com.arantec.castafiore.data.lyrics.LyricsProvider

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

// Added for dynamic theming
import androidx.palette.graphics.Palette
import androidx.core.graphics.toColorInt
import androidx.core.graphics.ColorUtils
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.arantec.castafiore.data.download.SongDownloadManager
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.utils.StatusBarUtils


class LyricsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLyricsBinding
    private var musicService: MusicService? = null
    private var isBound = false

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private var loadJob: Job? = null
    private var songChangeListener: ((Song?) -> Unit)? = null

    // Dynamic theming state
    private var lastAppliedBackgroundColor: Int? = null
    private lateinit var musicRepository: MusicRepository

    private data class OnColors(val primary: Int, val secondary: Int)

    private fun pickOnColors(background: Int): OnColors {
        val cb = ColorUtils.calculateContrast(Color.BLACK, background)
        val cw = ColorUtils.calculateContrast(Color.WHITE, background)
        val primary = if (cb >= cw) Color.BLACK else Color.WHITE
        val secondary = ColorUtils.setAlphaComponent(primary, 0x99)
        return OnColors(primary, secondary)
    }

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

        musicRepository = MusicRepository.getInstance(this)

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

        // LrcView setup
        binding.btnFollow.visibility = android.view.View.GONE
        binding.lrcView.setLabel(getString(R.string.no_lyrics))
        binding.lrcView.setDraggable(true) { _, timeMs ->
            musicService?.seekTo(timeMs)
            true
        }

        // Initial default theme
        applyDefaultTheme()

        // Bind service
        bindMusicService()
    }

    override fun onResume() {
        super.onResume()
        lastAppliedBackgroundColor?.let { StatusBarUtils.setSystemBarsColor(this, it) }
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
        // Reaplicar color de barras del sistema para consistencia al volver de Player
        lastAppliedBackgroundColor?.let { StatusBarUtils.setSystemBarsColor(this, it) }
    }

    private fun updateHeader(song: Song?) {
        if (song == null) {
            binding.tvSongTitle.text = ""
            binding.tvArtistName.text = ""
            // keep default theme
            applyDefaultTheme()
        } else {
            binding.tvSongTitle.text = song.title
            binding.tvArtistName.text = song.artist
            // attempt dynamic theming from cache -> cover
            val cached = com.arantec.castafiore.utils.ThemeColorCache.get(song)
            if (cached != null) {
                applyDynamicTheme(cached)
            } else {
                tryLoadCoverAndApplyTheme(song)
            }
        }
    }

    private fun loadLyricsFor(song: Song?) {
        // Always refresh header when loading lyrics
        updateHeader(song)
        if (song == null) {
            binding.lrcView.setLabel(getString(R.string.no_song))
            binding.lrcView.loadLrc("")
            return
        }
        // No overlay: rely on LrcView's label only
        loadJob?.cancel()
        loadJob = CoroutineScope(Dispatchers.Main).launch {
            val result = LyricsProvider.getInstance().getSyncedLyrics(applicationContext, song)
            if (result.isSuccess) {
                val lines = result.getOrNull().orEmpty()
                val lrc = linesToLrcText(lines)
                binding.lrcView.loadLrc(lrc)
                val currentPos = musicService?.getCurrentPosition() ?: 0L
                binding.lrcView.updateTime(currentPos)
                // Clear label when we have lyrics
                if (lrc.isNotBlank()) binding.lrcView.setLabel("")
            } else {
                binding.lrcView.setLabel(getString(R.string.no_lyrics))
                binding.lrcView.loadLrc("")
            }
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val pos = musicService?.getCurrentPosition() ?: 0L
                binding.lrcView.updateTime(pos)
                handler.postDelayed(this, 120L)
            }
        }
        handler.postDelayed(progressRunnable!!, 100L)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    // Theming helpers
    private fun applyDefaultTheme() {
        val color = "#121212".toColorInt()
        applyDynamicTheme(color)
    }

    private fun applyDynamicTheme(background: Int) {
        lastAppliedBackgroundColor = background
        // Background (apply to dedicated background view for consistency with Player)
        binding.lyricsBackground.setBackgroundColor(background)
        StatusBarUtils.setSystemBarsColor(this, background)
        // Foreground text/icon colors
        val on = pickOnColors(background)
        binding.tvSongTitle.setTextColor(on.primary)
        binding.tvArtistName.setTextColor(on.secondary)
        // Aplicar colores directos al LrcView del módulo local
        binding.lrcView.setCurrentColor(on.primary)
        binding.lrcView.setNormalColor(on.secondary)
        // Back button tint
        binding.btnBack.imageTintList = android.content.res.ColorStateList.valueOf(on.primary)
    }

    private fun tryLoadCoverAndApplyTheme(song: Song) {
        // Preferir portada local como en PlayerActivity
        val dm = SongDownloadManager.getInstance(this)
        val localCoverPath = try { dm.createCoverPath(song) } catch (_: Exception) { null }
        if (!localCoverPath.isNullOrEmpty()) {
            val file = java.io.File(localCoverPath)
            if (file.exists()) {
                BitmapFactory.decodeFile(localCoverPath)?.let { bmp ->
                    applyDynamicThemeFromBitmap(bmp)
                    return
                }
            }
        }

        // Fallback a URL remota (mismo formato que PlayerActivity)
        val (username, token, salt) = musicRepository.getAuthParams()
        val coverUrl = if (song.albumId != null) {
            "${musicRepository.serverUrl}/rest/getCoverArt.view?id=${song.albumId}&u=$username&t=$token&s=$salt&v=1.16.1&c=Castafiore&size=500"
        } else null
        if (coverUrl == null) {
            applyDefaultTheme()
            return
        }
        Glide.with(this)
            .asBitmap()
            .load(coverUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    applyDynamicThemeFromBitmap(resource)
                }
                override fun onLoadCleared(placeholder: Drawable?) { /* no-op */ }
                override fun onLoadFailed(errorDrawable: Drawable?) { applyDefaultTheme() }
            })
    }

    private fun applyDynamicThemeFromBitmap(bitmap: Bitmap) {
        val color = com.arantec.castafiore.utils.ImageUtils.extractBackgroundColor(bitmap)
        if (color != null) {
            // Cache for consistency with PlayerActivity
            musicService?.getCurrentSong()?.let { com.arantec.castafiore.utils.ThemeColorCache.put(it, color) }
            applyDynamicTheme(color)
        } else {
            applyDefaultTheme()
        }
    }

    // Ya no usamos RecyclerView ni ancla manual; LrcView maneja el scroll suavemente

    // Convierte nuestro modelo a texto LRC
    private fun linesToLrcText(lines: List<com.arantec.castafiore.data.lyrics.LyricsLine>): String {
        fun format(ms: Long): String {
            val totalSec = ms / 1000
            val mm = totalSec / 60
            val ss = totalSec % 60
            val cs = (ms % 1000) / 10 // centésimas (00–99)
            return String.format("[%02d:%02d.%02d]", mm, ss, cs)
        }
        val sb = StringBuilder()
        for (line in lines) {
            sb.append(format(line.timeMs))
                .append(line.text).append('\n')
        }
        return sb.toString()
    }
}
