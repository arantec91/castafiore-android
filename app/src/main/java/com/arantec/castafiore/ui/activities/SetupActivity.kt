package com.arantec.castafiore.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.arantec.castafiore.R
import com.arantec.castafiore.data.network.CastafioreClient
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ActivitySetupBinding
import com.arantec.castafiore.utils.StatusBarUtils
import android.view.WindowInsetsController
import kotlinx.coroutines.launch
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.core.widget.doOnTextChanged
import androidx.core.view.isVisible

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var musicRepository: MusicRepository

    private val FIXED_SERVER_URL = "http://65.109.23.109:8080"
    private var isLoading: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force system bar colors and disable edge-to-edge on Android 14+
        if (Build.VERSION.SDK_INT >= 34) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

            val darkColor = ContextCompat.getColor(this, R.color.background_primary)
            window.statusBarColor = darkColor
            window.navigationBarColor = darkColor

            // Prevent content from drawing under system bars
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure consistent status bar color
        StatusBarUtils.setStatusBarColor(this)
        // Ensure content does not draw under the status bar (adds top padding on API 34+)
        StatusBarUtils.applyStatusBarTopPadding(binding.root)

        // Post-enforcement for stubborn devices (API 34+)
        if (Build.VERSION.SDK_INT >= 34) {
            binding.root.post {
                val darkColor = ContextCompat.getColor(this, R.color.background_primary)
                window.statusBarColor = darkColor
                window.navigationBarColor = darkColor

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or
                                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                } else {
                    var flags = window.decorView.systemUiVisibility
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    window.decorView.systemUiVisibility = flags
                }

                window.decorView.setBackgroundColor(darkColor)
            }
        }

        musicRepository = MusicRepository.getInstance(this)

        // Load saved values (only username)
        binding.etUsername.setText(musicRepository.username ?: "")

        // Mostrar mensaje de credenciales caducadas si aplica
        intent.getStringExtra("expired_message")?.let { msg ->
            if (msg.isNotBlank()) {
                showStatus(msg, true)
            }
        }

        binding.btnConnect.text = getString(R.string.login)
        binding.btnConnect.setOnClickListener {
            submit()
        }
        binding.btnRetry.setOnClickListener { submit() }

        // Improve form UX: clear errors as user types
        binding.etUsername.doOnTextChanged { _, _, _, _ ->
            binding.tilUsername.error = null
            if (binding.tvStatus.isVisible) showStatus("", false)
            updateLoginEnabled()
        }
        binding.etPassword.doOnTextChanged { _, _, _, _ ->
            binding.tilPassword.error = null
            if (binding.tvStatus.isVisible) showStatus("", false)
            updateLoginEnabled()
        }

        // Submit from keyboard on password done
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }

        // Initial button enabled state
        updateLoginEnabled()
    }

    private fun updateLoginEnabled() {
        val hasUser = binding.etUsername.text?.isNotBlank() == true
        val hasPass = binding.etPassword.text?.isNotBlank() == true
        binding.btnConnect.isEnabled = !isLoading && hasUser && hasPass
    }

    private fun submit() {
        hideKeyboard()
        testConnection()
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        binding.etUsername.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRetry.isVisible = false
        binding.btnConnect.text = if (loading) getString(R.string.logging_in) else getString(R.string.login)
        updateLoginEnabled()
    }

    private fun testConnection() {
        val serverUrl = FIXED_SERVER_URL
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        // Validación con errores inline
        if (username.isEmpty()) {
            binding.tilUsername.error = "El nombre de usuario es requerido"
            binding.etUsername.requestFocus()
            return
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "La contraseña es requerida"
            binding.etPassword.requestFocus()
            return
        }

        // Limpiar estado previo
        binding.tilUsername.error = null
        binding.tilPassword.error = null
        showStatus("", false)

        setLoading(true)
        showStatus("Verificando credenciales...", false)

        lifecycleScope.launch {
            try {
                // Initialize client with fixed server URL
                val client = CastafioreClient.initialize(this@SetupActivity, serverUrl)
                client.setCredentials(username, password)

                val response = client.ping()

                if (response.isSuccessful) {
                    val body = response.body()
                    android.util.Log.d("SetupActivity", "Respuesta exitosa: $body")
                    if (body?.subsonicResponse?.status == "ok") {
                        // Save credentials with fixed server
                        musicRepository.serverUrl = serverUrl
                        musicRepository.username = username
                        musicRepository.password = password

                        showStatus("✓ Inicio de sesión exitoso", false)

                        // Navigate to main activity
                        val intent = Intent(this@SetupActivity, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        val errorMessage = body?.subsonicResponse?.error?.message ?: "Error desconocido"
                        android.util.Log.e("SetupActivity", "Error en respuesta exitosa: $body")
                        when (body?.subsonicResponse?.error?.code) {
                            40 -> {
                                binding.tilUsername.error = getString(R.string.invalid_credentials)
                                binding.tilPassword.error = getString(R.string.invalid_credentials)
                                showStatus("Usuario o contraseña incorrectos", true)
                            }
                            50 -> showStatus("Usuario no autorizado para esta operación", true)
                            else -> showStatus("Error del servidor: $errorMessage", true)
                        }
                    }
                } else {
                    android.util.Log.e("SetupActivity", "Respuesta fallida: code=${response.code()} message=${response.message()} errorBody=${response.errorBody()?.string()}")
                    when (response.code()) {
                        401 -> {
                            binding.tilUsername.error = getString(R.string.invalid_credentials)
                            binding.tilPassword.error = getString(R.string.invalid_credentials)
                            showStatus("Credenciales inválidas", true)
                        }
                        403 -> showStatus("Acceso denegado", true)
                        404 -> showStatus("Servidor no disponible", true)
                        500 -> showStatus("Error interno del servidor", true)
                        else -> showStatus("Error de conexión: ${response.code()} - ${response.message()}", true)
                    }
                }

            } catch (e: java.net.UnknownHostException) {
                android.util.Log.e("SetupActivity", "UnknownHostException", e)
                showStatus("No se puede conectar al servidor", true)
            } catch (e: java.net.ConnectException) {
                android.util.Log.e("SetupActivity", "ConnectException", e)
                showStatus("Error de conexión. Reintenta más tarde", true)
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.e("SetupActivity", "SocketTimeoutException", e)
                showStatus("Tiempo de conexión agotado. Intenta nuevamente", true)
            } catch (e: javax.net.ssl.SSLException) {
                android.util.Log.e("SetupActivity", "SSLException", e)
                showStatus("Error de certificado SSL", true)
            } catch (e: Exception) {
                android.util.Log.e("SetupActivity", "Excepción inesperada", e)
                showStatus("Error inesperado: ${e.message}", true)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.tvStatus.text = message
        if (message.isEmpty()) {
            binding.tvStatus.visibility = View.GONE
            binding.btnRetry.isVisible = false
        } else {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isError) R.color.error else R.color.success
                )
            )
            binding.btnRetry.isVisible = isError && !isLoading
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
