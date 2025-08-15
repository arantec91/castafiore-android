package com.arantec.castafiore.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.arantec.castafiore.databinding.FragmentAccountBinding
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.utils.StatusBarUtils
import kotlinx.coroutines.launch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class AccountFragment : Fragment() {

    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        StatusBarUtils.setStatusBarColor(this)
        _binding = FragmentAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Apply system bar insets to avoid overlap
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = sysBars.top)
            insets
        }

        loadUserInfo()
    }

    private fun loadUserInfo() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val result = musicRepository.getCurrentUserInfo()
                val username = if (result.isSuccess) {
                    result.getOrNull()?.get("username")?.asString
                } else null
                val display = username ?: musicRepository.username ?: ""
                binding.tvUserValue.text = display
            } catch (_: Exception) {
                val display = musicRepository.username ?: ""
                binding.tvUserValue.text = display
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
