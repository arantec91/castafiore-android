package com.arantec.castafiore.ui.fragments

import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.arantec.castafiore.R
import com.arantec.castafiore.data.cache.CacheManager
import com.arantec.castafiore.databinding.FragmentStorageBinding
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.utils.StatusBarUtils
import com.arantec.castafiore.utils.snack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StorageFragment : Fragment() {

    private var _binding: FragmentStorageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        StatusBarUtils.setStatusBarColor(this)
        _binding = FragmentStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Show Delete Downloads section and enable its action
        binding.tvTitleDeleteDownloads.visibility = View.VISIBLE
        binding.tvDescDeleteDownloads.visibility = View.VISIBLE
        binding.btnDeleteDownloads.visibility = View.VISIBLE
        binding.dividerAfterDelete.visibility = View.VISIBLE

        // Enable click listener for delete downloads
        binding.btnDeleteDownloads.setOnClickListener { confirmAndDeleteDownloads() }
        binding.btnClearCache.setOnClickListener { clearCache() }

        // Initial storage status
        updateStorageStatus()
    }

    private fun confirmAndDeleteDownloads() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.storage_delete_confirm_title))
            .setMessage(getString(R.string.storage_delete_confirm_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteAllDownloads() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteAllDownloads() {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    var allOk = true

                    // 1) Delete MediaStore-backed downloads via DownloadManager
                    val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())
                    val songs = try { dm.getAllDownloadedSongs() } catch (_: Exception) { emptyList() }
                    if (songs.isNotEmpty()) {
                        val ids = songs.map { it.id }
                        val deleted = try { dm.deleteMultipleSongs(ids) } catch (_: Exception) { 0 }
                        if (deleted != ids.size) {
                            allOk = false
                        }
                    }

                    // 2) Delete legacy private external files (if any remain)
                    val musicRoot = requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.resolve("Castafiore")
                    val picturesRoot = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.resolve("Castafiore")
                    if (musicRoot != null) {
                        allOk = deleteRecursivelySafe(musicRoot) && allOk
                    }
                    if (picturesRoot != null) {
                        allOk = deleteRecursivelySafe(picturesRoot) && allOk
                    }

                    allOk
                } catch (_: Exception) {
                    false
                }
            }
            setBusy(false)
            if (success) {
                snack(getString(R.string.storage_delete_success))
            } else {
                snack(getString(R.string.storage_delete_error))
            }
            updateStorageStatus()
        }
    }

    private fun clearCache() {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            // Clear memory cache on main thread
            try {
                ImageLoader.clearMemoryCache(requireContext())
            } catch (_: Exception) { }

            val success = withContext(Dispatchers.IO) {
                try {
                    // Clear image disk cache (runs in IO inside ImageLoader)
                    ImageLoader.clearDiskCache(requireContext())

                    // Clear app cache data structures
                    CacheManager.getInstance(requireContext()).clearAllCache()

                    // Clear temporary cache directories (do not touch downloads)
                    requireContext().cacheDir?.let { deleteRecursivelySafe(it) }
                    requireContext().externalCacheDir?.let { deleteRecursivelySafe(it) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            setBusy(false)
            if (success) {
                snack(getString(R.string.storage_clear_cache_success))
            } else {
                snack(getString(R.string.storage_clear_cache_error))
            }
            updateStorageStatus()
        }
    }

    private fun deleteRecursivelySafe(file: File): Boolean {
        return try {
            if (!file.exists()) return true
            if (file.isFile) return file.delete()
            var allOk = true
            file.listFiles()?.forEach { child ->
                allOk = deleteRecursivelySafe(child) && allOk
            }
            // After children deleted, delete the folder itself
            allOk = file.delete() && allOk
            allOk
        } catch (_: Exception) {
            false
        }
    }

    private fun sizeOfRecursively(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return runCatching { file.length() }.getOrDefault(0L)
        var total = 0L
        file.listFiles()?.forEach { child ->
            total += sizeOfRecursively(child)
        }
        return total
    }

    private fun updateStorageStatus() {
        if (!isAdded || _binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val dm = com.arantec.castafiore.data.download.SongDownloadManager.getInstance(requireContext())
            val stats = withContext(Dispatchers.IO) {
                // Base storage stats from the same volume as external files
                val baseDir = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
                val statFs = StatFs(baseDir.absolutePath)
                val totalBytes = runCatching { statFs.totalBytes }.getOrDefault(0L)
                val availableBytes = runCatching { statFs.availableBytes }.getOrDefault(0L)
                val usedBytes = (totalBytes - availableBytes).coerceAtLeast(0L)

                // Castafiore downloads: MediaStore + legacy music dir
                val downloadedSongs = runCatching { dm.getAllDownloadedSongs() }.getOrElse { emptyList() }
                var downloadsBytes = 0L
                downloadedSongs.forEach { s ->
                    downloadsBytes += runCatching { dm.getSongFileSize(s.id) }.getOrDefault(0L)
                }
                val legacyMusicDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.resolve("Castafiore")
                downloadsBytes += sizeOfRecursively(legacyMusicDir)

                // Castafiore cache: internal/external cache + covers directory
                var cacheBytes = 0L
                cacheBytes += sizeOfRecursively(requireContext().cacheDir)
                cacheBytes += sizeOfRecursively(requireContext().externalCacheDir)
                val coversDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.resolve("Castafiore/Covers")
                cacheBytes += sizeOfRecursively(coversDir)

                val otherAppsBytes = (usedBytes - downloadsBytes - cacheBytes).coerceAtLeast(0L)
                val percent = if (totalBytes > 0) ((usedBytes * 100.0) / totalBytes).toInt().coerceIn(0, 100) else 0

                StorageStats(
                    total = totalBytes,
                    used = usedBytes,
                    available = availableBytes,
                    downloads = downloadsBytes,
                    cache = cacheBytes,
                    otherApps = otherAppsBytes,
                    percent = percent
                )
            }

            if (!isAdded || _binding == null) return@launch

            // Apply to UI
            val fmt = { b: Long -> dm.formatFileSize(b) }
            binding.tvStorageSummary.text = getString(
                R.string.storage_summary_format,
                fmt(stats.used), fmt(stats.total), stats.percent
            )
            applySegmentWeights(stats)
            binding.tvCastDownloadsSize.text = fmt(stats.downloads)
            binding.tvCastCacheSize.text = fmt(stats.cache)
            binding.tvAvailableSize.text = fmt(stats.available)
            binding.tvOtherAppsSize.text = fmt(stats.otherApps)
        }
    }

    private fun applySegmentWeights(stats: StorageStats) {
        if (!isAdded || _binding == null) return
        // Use bytes directly as weights; if all zero, fallback to equal weights
        val wOther = stats.otherApps.toFloat()
        val wDown = stats.downloads.toFloat()
        val wCache = stats.cache.toFloat()
        val wAvail = stats.available.toFloat()
        val sum = wOther + wDown + wCache + wAvail
        val wo: Float
        val wd: Float
        val wc: Float
        val wa: Float
        if (sum <= 0f) {
            wo = 1f; wd = 1f; wc = 1f; wa = 1f
        } else {
            wo = wOther; wd = wDown; wc = wCache; wa = wAvail
        }
        (binding.segOther.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = wo
            binding.segOther.layoutParams = this
        }
        (binding.segDownloads.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = wd
            binding.segDownloads.layoutParams = this
        }
        (binding.segCache.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = wc
            binding.segCache.layoutParams = this
        }
        (binding.segAvailable.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = wa
            binding.segAvailable.layoutParams = this
        }
    }

    private data class StorageStats(
        val total: Long,
        val used: Long,
        val available: Long,
        val downloads: Long,
        val cache: Long,
        val otherApps: Long,
        val percent: Int
    )

    private fun setBusy(busy: Boolean) {
        binding.btnDeleteDownloads.isEnabled = !busy
        binding.btnClearCache.isEnabled = !busy
    }

    override fun onResume() {
        super.onResume()
        StatusBarUtils.setStatusBarColor(this)
        updateStorageStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
