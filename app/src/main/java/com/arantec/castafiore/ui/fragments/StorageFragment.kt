package com.arantec.castafiore.ui.fragments

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.arantec.castafiore.R
import com.arantec.castafiore.data.cache.CacheManager
import com.arantec.castafiore.databinding.FragmentStorageBinding
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.utils.StatusBarUtils
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

        binding.btnDeleteDownloads.setOnClickListener { confirmAndDeleteDownloads() }
        binding.btnClearCache.setOnClickListener { clearCache() }
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
                toast(getString(R.string.storage_delete_success))
            } else {
                toast(getString(R.string.storage_delete_error))
            }
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
                toast(getString(R.string.storage_clear_cache_success))
            } else {
                toast(getString(R.string.storage_clear_cache_error))
            }
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

    private fun setBusy(busy: Boolean) {
        binding.btnDeleteDownloads.isEnabled = !busy
        binding.btnClearCache.isEnabled = !busy
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        StatusBarUtils.setStatusBarColor(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
