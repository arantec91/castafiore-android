package com.arantec.castafiore.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arantec.castafiore.data.network.CastafioreClient
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ActivitySplashBinding
import com.arantec.castafiore.utils.StatusBarUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var musicRepository: MusicRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure consistent status bar color on splash
        StatusBarUtils.setStatusBarColor(this)

        // Mostrar la versión de la aplicación desde BuildConfig
        binding.tvVersion.text = "v${com.arantec.castafiore.BuildConfig.VERSION_NAME}"

        musicRepository = MusicRepository.getInstance(this)

        // Verificar sesión después de un pequeño delay para mostrar la splash
        lifecycleScope.launch {
            delay(1500) // Mostrar splash por 1.5 segundos
            checkUserSession()
        }
    }

    private fun checkUserSession() {
        if (musicRepository.isConfigured()) {
            // Validar credenciales contra el servidor de forma rápida
            lifecycleScope.launch {
                try {
                    val server = musicRepository.serverUrl
                    if (!server.isNullOrEmpty()) {
                        val client = CastafioreClient.initialize(this@SplashActivity, server)
                        val username = musicRepository.username ?: ""
                        val password = musicRepository.password ?: ""
                        client.setCredentials(username, password)
                        
                        val response = client.ping()

                        if (response.isSuccessful) {
                            val body = response.body()
                            val status = body?.subsonicResponse?.status
                            if (status == "ok") {
                                navigateToMain()
                                return@launch
                            } else {
                                val code = body?.subsonicResponse?.error?.code
                                if (code == 40 || code == 50) {
                                    // Credenciales inválidas o usuario sin permiso
                                    handleExpiredCredentials()
                                    return@launch
                                } else {
                                    // Otros errores del servidor: no bloquear inicio
                                    navigateToMain()
                                    return@launch
                                }
                            }
                        } else {
                            // HTTP inválido: 401/403 indican credenciales expiradas/eliminadas
                            val httpCode = response.code()
                            if (httpCode == 401 || httpCode == 403) {
                                handleExpiredCredentials()
                                return@launch
                            } else {
                                // Otros códigos: continuar a Main (posible caída temporal)
                                navigateToMain()
                                return@launch
                            }
                        }
                    } else {
                        // Sin servidor guardado aunque esté configurado: ir a Setup
                        navigateToSetup()
                        return@launch
                    }
                } catch (_: Exception) {
                    // Errores de red u otros: permitir entrada a Main para modo offline
                    navigateToMain()
                    return@launch
                }
            }
        } else {
            // Usuario no tiene configuración, ir a SetupActivity
            navigateToSetup()
        }
    }

    private fun handleExpiredCredentials() {
        // Limpiar credenciales guardadas y navegar a Setup con mensaje
        musicRepository.username = null
        musicRepository.password = null
        musicRepository.serverUrl = null

        val intent = Intent(this, SetupActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        intent.putExtra("expired_message", "Tus credenciales han caducado, ponte en contacto con el administrador.")
        startActivity(intent)
        finish()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToSetup() {
        val intent = Intent(this, SetupActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
