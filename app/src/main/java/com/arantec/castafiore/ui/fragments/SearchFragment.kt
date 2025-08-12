package com.arantec.castafiore.ui.fragments

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.arantec.castafiore.R
import com.arantec.castafiore.databinding.FragmentSearchBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

class SearchFragment : Fragment() {

    companion object {
        private const val SEARCH_DELAY_MS = 300L
        private const val ANIMATION_DURATION = 250L
    }

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    // Search state
    private var searchJob: Job? = null
    private var currentSearchQuery = ""
    private var isVoiceSearchActive = false
    private var currentViewMode = ViewMode.RESULTS

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleVoiceSearchResult(result.resultCode, result.data)
    }

    enum class ViewMode {
        SUGGESTIONS,
        HISTORY,
        RESULTS
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupSearchFunctionality()
    }

    private fun setupUI() {
        // Configurar animaciones de entrada solo si los elementos existen en el binding
        binding.root.alpha = 0f
        binding.root.translationY = -100f

        binding.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun setupSearchFunctionality() {
        // Configurar búsqueda básica si el EditText existe
        try {
            // Solo intentar acceder a elementos que podrían existir usando un ID genérico
            val searchEditText = binding.root.findViewById<android.widget.EditText>(android.R.id.edit)
            searchEditText?.let { editText ->
                editText.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s?.toString()?.trim() ?: ""
                        handleSearchTextChange(query)
                    }

                    override fun afterTextChanged(s: Editable?) {}
                })

                editText.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        performSearch(editText.text.toString().trim())
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (_: Exception) {
            // Ignorar errores de binding
        }
    }

    private fun handleSearchTextChange(query: String) {
        currentSearchQuery = query

        // Cancelar búsqueda anterior
        searchJob?.cancel()

        when {
            query.isEmpty() -> {
                switchToViewMode(ViewMode.HISTORY)
            }
            query.length < 2 -> {
                switchToViewMode(ViewMode.SUGGESTIONS)
            }
            else -> {
                // Búsqueda con delay para evitar demasiadas consultas
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DELAY_MS)
                    if (query == currentSearchQuery) {
                        performSearch(query)
                    }
                }
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return

        switchToViewMode(ViewMode.RESULTS)

        // Simular búsqueda básica
        showSnackbar("Buscando: $query")
    }

    private fun startVoiceSearch() {
        try {
            isVoiceSearchActive = true

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            voiceSearchLauncher.launch(intent)
        } catch (_: Exception) {
            isVoiceSearchActive = false
            Toast.makeText(context, "Búsqueda por voz no soportada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleVoiceSearchResult(resultCode: Int, data: Intent?) {
        isVoiceSearchActive = false

        if (resultCode == Activity.RESULT_OK && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!results.isNullOrEmpty()) {
                val spokenText = results[0]
                performSearch(spokenText)
            }
        }
    }

    private fun switchToViewMode(mode: ViewMode) {
        if (currentViewMode == mode) return

        currentViewMode = mode

        // Animar transición básica
        binding.root.animate()
            .alpha(0.5f)
            .setDuration(ANIMATION_DURATION / 2)
            .withEndAction {
                binding.root.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION / 2)
                    .start()
            }
            .start()
    }

    private fun showSnackbar(message: String, isError: Boolean = false) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
        if (isError) {
            snackbar.setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.error))
        }
        snackbar.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        _binding = null
    }
}
