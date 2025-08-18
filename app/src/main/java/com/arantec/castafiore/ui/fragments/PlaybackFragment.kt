package com.arantec.castafiore.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.arantec.castafiore.R
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

        // Continue-with-similar switch
        binding.switchContinueSimilar.isChecked = musicRepository.continueWithSimilarEnabled
        binding.switchContinueSimilar.setOnCheckedChangeListener { _, isChecked ->
            musicRepository.continueWithSimilarEnabled = isChecked
        }

        // Audio quality radios: default to High if not set
        val highDefault = musicRepository.highQualityEnabled
        binding.radioHighQuality.isChecked = highDefault
        binding.radioBasicQuality.isChecked = !highDefault

        fun selectHigh() {
            binding.radioHighQuality.isChecked = true
            binding.radioBasicQuality.isChecked = false
            musicRepository.highQualityEnabled = true
        }
        fun selectBasic() {
            binding.radioHighQuality.isChecked = false
            binding.radioBasicQuality.isChecked = true
            musicRepository.highQualityEnabled = false
        }

        // Radio button changes
        binding.radioHighQuality.setOnClickListener { selectHigh() }
        binding.radioBasicQuality.setOnClickListener { selectBasic() }

        // Make entire rows clickable
        binding.optionHighQuality.setOnClickListener { selectHigh() }
        binding.optionBasicQuality.setOnClickListener { selectBasic() }
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
