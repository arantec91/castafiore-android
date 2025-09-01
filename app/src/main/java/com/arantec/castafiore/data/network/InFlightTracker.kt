package com.arantec.castafiore.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * App-scoped tracker of in-flight network requests. Use with OkHttp interceptor.
 */
object InFlightTracker {
    private val appScope = CoroutineScope(Dispatchers.Default)
    private val _count = MutableStateFlow(0)

    // Public boolean state to observe
    val isLoading: StateFlow<Boolean> = _count
        .map { it > 0 }
        .stateIn(appScope, SharingStarted.Eagerly, false)

    fun begin() {
        appScope.launch {
            _count.value = _count.value + 1
        }
    }

    fun end() {
        appScope.launch {
            _count.value = max(0, _count.value - 1)
        }
    }
}

