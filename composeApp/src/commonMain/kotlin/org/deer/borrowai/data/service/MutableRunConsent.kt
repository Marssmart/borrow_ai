package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A per-run user consent that starts revoked, can be granted, and is revoked again when a new run starts.
 */
class MutableRunConsent {
    private val mutableGranted = MutableStateFlow(false)

    val granted: StateFlow<Boolean> = mutableGranted.asStateFlow()

    fun grant() {
        mutableGranted.value = true
    }

    fun reset() {
        mutableGranted.value = false
    }
}
