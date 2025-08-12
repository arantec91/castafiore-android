package com.arantec.castafiore.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arantec.castafiore.data.repository.MusicRepository
import com.arantec.castafiore.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var musicRepository: MusicRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        musicRepository = MusicRepository.getInstance(this)

        // Verificar sesión después de un pequeño delay para mostrar la splash
        lifecycleScope.launch {
            delay(1500) // Mostrar splash por 1.5 segundos
            checkUserSession()
        }
    }

    private fun checkUserSession() {
        if (musicRepository.isConfigured()) {
            // Usuario ya tiene configuración guardada, ir directamente a MainActivity
            navigateToMain()
        } else {
            // Usuario no tiene configuración, ir a SetupActivity
            navigateToSetup()
        }
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
