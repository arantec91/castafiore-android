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
import com.arantec.castafiore.utils.StatusBarUtils
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var musicRepository: MusicRepository

    private val FIXED_SERVER_URL = "http://65.109.23.109:4533"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure consistent status bar color
        StatusBarUtils.setStatusBarColor(this)

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
            testConnection()
        }
    }

    private fun testConnection() {
        val serverUrl = FIXED_SERVER_URL
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        // Validación simple: solo mensaje principal
        if (username.isEmpty()) {
            showStatus("El nombre de usuario es requerido", true)
            binding.etUsername.requestFocus()
            return
        }
        if (password.isEmpty()) {
            showStatus("La contraseña es requerida", true)
            binding.etPassword.requestFocus()
            return
        }

        // Limpiar estado previo
        showStatus("", false)

        binding.btnConnect.isEnabled = false
        binding.btnConnect.text = getString(R.string.logging_in)
        showStatus("Verificando credenciales...", false)

        lifecycleScope.launch {
            try {
                // Initialize client with fixed server URL
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
                        when (body?.subsonicResponse?.error?.code) {
                            40 -> showStatus("Usuario o contraseña incorrectos", true)
                            50 -> showStatus("Usuario no autorizado para esta operación", true)
                            else -> showStatus("Error del servidor: $errorMessage", true)
                        }
                    }
                } else {
                    when (response.code()) {
                        401 -> showStatus("Credenciales inválidas", true)
                        403 -> showStatus("Acceso denegado", true)
                        404 -> showStatus("Servidor no disponible", true)
                        500 -> showStatus("Error interno del servidor", true)
                        else -> showStatus("Error de conexión: ${response.code()} - ${response.message()}", true)
                    }
                }

            } catch (_: java.net.UnknownHostException) {
                showStatus("No se puede conectar al servidor", true)
            } catch (_: java.net.ConnectException) {
                showStatus("Error de conexión. Reintenta más tarde", true)
            } catch (_: java.net.SocketTimeoutException) {
                showStatus("Tiempo de conexión agotado. Intenta nuevamente", true)
            } catch (_: javax.net.ssl.SSLException) {
                showStatus("Error de certificado SSL", true)
            } catch (e: Exception) {
                showStatus("Error inesperado: ${e.message}", true)
            } finally {
                binding.btnConnect.isEnabled = true
                binding.btnConnect.text = getString(R.string.login)
            }
        }
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
