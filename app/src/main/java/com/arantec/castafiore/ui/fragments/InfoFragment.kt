package com.arantec.castafiore.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.arantec.castafiore.R
import com.arantec.castafiore.databinding.FragmentInfoBinding
import com.arantec.castafiore.utils.StatusBarUtils

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        StatusBarUtils.setStatusBarColor(this)
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val versionName = try {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0)
                .versionName
        } catch (_: Exception) {
            getString(R.string.settings_unknown_version)
        }
        binding.tvVersionLabel.text = getString(R.string.settings_app_version_title)
        binding.tvVersionValue.text = versionName
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

