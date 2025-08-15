package com.arantec.castafiore.ui.fragments

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.FragmentSettingsBinding
import com.arantec.castafiore.ui.activities.SetupActivity
import com.arantec.castafiore.utils.ImageLoader
import com.arantec.castafiore.utils.StatusBarUtils
import kotlinx.coroutines.launch

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

        setupUI()
        loadServerInfo()
        setupClickListeners()
    }

    private fun setupUI() {
        // Configurar toolbar
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        // Mostrar versión de la app usando PackageManager
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.tvAppVersion.text = packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            binding.tvAppVersion.text = "1.0.0"
        }
    }

    private fun loadServerInfo() {
        if (musicRepository.isConfigured()) {
            binding.tvServerUrl.text = musicRepository.serverUrl ?: "Sin servidor configurado"
            binding.tvUsername.text = "Usuario: ${musicRepository.username ?: "Sin usuario"}"
            binding.btnDisconnect.isEnabled = true
        } else {
            binding.tvServerUrl.text = "Sin servidor configurado"
            binding.tvUsername.text = "Sin usuario configurado"
            binding.btnDisconnect.isEnabled = false
        }
    }

    private fun setupClickListeners() {
        // Botón para desconectar del servidor
        binding.btnDisconnect.setOnClickListener {
            showDisconnectConfirmation()
        }

        // Botón para limpiar caché
        binding.btnClearCache.setOnClickListener {
            clearImageCache()
        }

        // Switch: continuar con canciones similares
        binding.switchContinueWithSimilar.isChecked = musicRepository.continueWithSimilarEnabled
        binding.switchContinueWithSimilar.setOnCheckedChangeListener { _, isChecked ->
            musicRepository.continueWithSimilarEnabled = isChecked
            showMessage(if (isChecked) "Reproducir similares activado" else "Reproducir similares desactivado")
        }
    }

    private fun showDisconnectConfirmation() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Desconectar del servidor")
            .setMessage("¿Estás seguro de que deseas desconectarte del servidor? Tendrás que configurar la conexión nuevamente.")
            .setPositiveButton("Desconectar") { _, _ ->
                disconnectFromServer()
            }
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.show()
    }

    private fun disconnectFromServer() {
        showLoading(true)

        lifecycleScope.launch {
            try {
                // Limpiar todas las configuraciones del servidor
                musicRepository.serverUrl = null
                musicRepository.username = null
                musicRepository.password = null

                // Limpiar caché de imágenes
                ImageLoader.clearMemoryCache(requireContext())
                ImageLoader.clearDiskCache(requireContext())

                showLoading(false)
                showMessage("Desconectado del servidor exitosamente")

                // Navegar a la pantalla de configuración inicial
                navigateToSetup()

            } catch (e: Exception) {
                showLoading(false)
                showError("Error al desconectar del servidor: ${e.message}")
            }
        }
    }

    private fun clearImageCache() {
        showLoading(true)

        lifecycleScope.launch {
            try {
                // Limpiar caché de memoria
                ImageLoader.clearMemoryCache(requireContext())

                // Limpiar caché de disco
                ImageLoader.clearDiskCache(requireContext())

                showLoading(false)
                showMessage("Caché de imágenes limpiado exitosamente")

            } catch (e: Exception) {
                showLoading(false)
                showError("Error al limpiar caché: ${e.message}")
            }
        }
    }

    private fun navigateToSetup() {
        try {
            // Crear intent para ir a SetupActivity
            val intent = Intent(requireContext(), SetupActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)

            // Finalizar la actividad actual
            requireActivity().finish()

        } catch (e: Exception) {
            showError("Error al navegar a configuración inicial")
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE

        // Deshabilitar interacciones mientras carga
        binding.btnDisconnect.isEnabled = !show && musicRepository.isConfigured()
        binding.btnClearCache.isClickable = !show
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()

        // Ensure consistent status bar color on resume
        StatusBarUtils.setStatusBarColor(this)

        // Recargar información del servidor por si cambió
        loadServerInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
