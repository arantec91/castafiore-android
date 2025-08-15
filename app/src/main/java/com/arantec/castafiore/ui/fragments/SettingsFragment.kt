package com.arantec.castafiore.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.arantec.castafiore.R
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentSettingsBinding
import com.arantec.castafiore.ui.activities.SetupActivity
import com.arantec.castafiore.ui.adapters.SettingsAdapter
import com.arantec.castafiore.utils.StatusBarUtils

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var musicRepository: MusicRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Ensure consistent status bar color using utility
        StatusBarUtils.setStatusBarColor(this)

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicRepository = MusicRepository.getInstance(requireContext())

        setupToolbar()
        setupRecycler()
        // Wire bottom logout button
        binding.btnLogout.setOnClickListener { showLogoutConfirmation() }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun setupRecycler() {
        val items = listOf(
            SettingsAdapter.SettingItem(
                id = "account",
                title = getString(R.string.settings_option_account),
                iconRes = R.drawable.ic_person
            ),
            SettingsAdapter.SettingItem(
                id = "playback",
                title = getString(R.string.settings_option_playback),
                iconRes = R.drawable.ic_queue_music
            ),
            SettingsAdapter.SettingItem(
                id = "info",
                title = getString(R.string.settings_option_info),
                iconRes = R.drawable.ic_info
            )
        )

        val adapter = SettingsAdapter(items) { item ->
            when (item.id) {
                "account" -> findNavController().navigate(R.id.accountFragment)
                "playback" -> findNavController().navigate(R.id.playbackFragment)
                "info" -> showAppInfoDialog()
            }
        }

        binding.rvSettings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSettings.adapter = adapter
    }

    private fun showLogoutConfirmation() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_logout_title))
            .setMessage(getString(R.string.settings_logout_message))
            .setPositiveButton(getString(R.string.settings_logout)) { _, _ -> logout() }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.show()
    }

    private fun logout() {
        showLoading(true)
        try {
            musicRepository.serverUrl = null
            musicRepository.username = null
            musicRepository.password = null
            showLoading(false)
            showMessage(getString(R.string.settings_logout_success))
            navigateToSetup()
        } catch (e: Exception) {
            showLoading(false)
            showError(getString(R.string.settings_logout_error, e.message ?: ""))
        }
    }

    private fun showAppInfoDialog() {
        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) {
            getString(R.string.settings_unknown_version)
        }
        val msg = "Versión: $versionName"
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_option_info))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.cancel), null)
            .show()
    }

    private fun navigateToSetup() {
        val intent = Intent(requireContext(), SetupActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        requireActivity().finish()
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
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
