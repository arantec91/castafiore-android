package com.arantec.castafiore.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentPlaybackBinding
import com.arantec.castafiore.utils.StatusBarUtils

class PlaybackFragment : Fragment() {

    private var _binding: FragmentPlaybackBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        StatusBarUtils.setStatusBarColor(this)
        _binding = FragmentPlaybackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Initialize switch state from repository
        binding.switchContinueSimilar.isChecked = musicRepository.continueWithSimilarEnabled

        binding.switchContinueSimilar.setOnCheckedChangeListener { _, isChecked ->
            musicRepository.continueWithSimilarEnabled = isChecked
        }
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

