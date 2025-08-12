package com.arantec.castafiore.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arantec.castafiore.R
import com.arantec.castafiore.data.network.NavidromeClient
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ActivitySetupBinding
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var musicRepository: MusicRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        musicRepository = MusicRepository.getInstance(this)

        // Load saved values
        binding.etServerUrl.setText(musicRepository.serverUrl ?: "")
        binding.etUsername.setText(musicRepository.username ?: "")

        binding.btnConnect.setOnClickListener {
            testConnection()
        }
    }

    private fun testConnection() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        // Validar campos vacíos
        if (serverUrl.isEmpty()) {
            showFieldError("etServerUrl", "La URL del servidor es requerida")
            return
        }

        if (username.isEmpty()) {
            showFieldError("etUsername", "El nombre de usuario es requerido")
            return
        }

        if (password.isEmpty()) {
            showFieldError("etPassword", "La contraseña es requerida")
            return
        }

        // Limpiar errores anteriores
        clearFieldErrors()

        binding.btnConnect.isEnabled = false
        binding.btnConnect.text = "Conectando..."
        showStatus("Verificando credenciales...", false)

        lifecycleScope.launch {
            try {
                // Test connection
                val client = NavidromeClient
                client.initialize(serverUrl)

                val (user, token, salt) = NavidromeClient.generateAuthParams(username, password)
                val response = client.getApiService().ping(
                    username = user,
                    token = token,
                    salt = salt,
                    version = "1.16.1",
                    client = "Castafiore"
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.subsonicResponse?.status == "ok") {
                        // Save credentials
                        musicRepository.serverUrl = serverUrl
                        musicRepository.username = username
                        musicRepository.password = password

                        showStatus("✓ Conexión exitosa", false)

                        // Navigate to main activity
                        val intent = Intent(this@SetupActivity, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        // Error en la respuesta del servidor
                        val errorMessage = body?.subsonicResponse?.error?.message ?: "Error desconocido"
                        when (body?.subsonicResponse?.error?.code) {
                            40 -> showAuthError("Usuario o contraseña incorrectos")
                            50 -> showAuthError("Usuario no autorizado para esta operación")
                            else -> showStatus("Error del servidor: $errorMessage", true)
                        }
                    }
                } else {
                    // Errores HTTP
                    when (response.code()) {
                        401 -> showAuthError("Credenciales inválidas")
                        403 -> showAuthError("Acceso denegado")
                        404 -> showStatus("Servidor no encontrado. Verifica la URL", true)
                        500 -> showStatus("Error interno del servidor", true)
                        else -> showStatus("Error de conexión: ${response.code()} - ${response.message()}", true)
                    }
                }

            } catch (e: java.net.UnknownHostException) {
                showStatus("No se puede conectar al servidor. Verifica la URL", true)
            } catch (e: java.net.ConnectException) {
                showStatus("Error de conexión. Verifica la URL y tu conexión a internet", true)
            } catch (e: java.net.SocketTimeoutException) {
                showStatus("Tiempo de conexión agotado. Intenta nuevamente", true)
            } catch (e: javax.net.ssl.SSLException) {
                showStatus("Error de certificado SSL. Verifica la URL", true)
            } catch (e: Exception) {
                showStatus("Error inesperado: ${e.message}", true)
            } finally {
                binding.btnConnect.isEnabled = true
                binding.btnConnect.text = "Conectar"
            }
        }
    }

    private fun showAuthError(message: String) {
        showStatus("❌ $message", true)
        // Resaltar campos de usuario y contraseña
        binding.etUsername.error = "Verifica tus credenciales"
        binding.etPassword.error = "Verifica tus credenciales"
    }

    private fun showFieldError(fieldName: String, message: String) {
        when (fieldName) {
            "etServerUrl" -> binding.etServerUrl.error = message
            "etUsername" -> binding.etUsername.error = message
            "etPassword" -> binding.etPassword.error = message
        }
    }

    private fun clearFieldErrors() {
        binding.etServerUrl.error = null
        binding.etUsername.error = null
        binding.etPassword.error = null
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.tvStatus.text = message
        if (message.isEmpty()) {
            binding.tvStatus.visibility = View.GONE
        } else {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isError) R.color.error else R.color.success
                )
            )
        }
    }
}
