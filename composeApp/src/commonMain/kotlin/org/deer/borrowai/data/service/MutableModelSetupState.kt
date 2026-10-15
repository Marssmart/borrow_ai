package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MutableModelSetupState {
    private val mutableState = MutableStateFlow(ModelSetupState())

    val state: StateFlow<ModelSetupState> = mutableState.asStateFlow()

    val value: ModelSetupState
        get() = mutableState.value

    val isReady: Boolean
        get() = value.isReady

    fun transition(
        phase: ModelSetupPhase,
        progress: Float = 0f,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
    ) {
        mutableState.value = ModelSetupState(
            phase = phase,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
        )
    }

    fun fail(reason: ModelSetupFailureReason) {
        mutableState.update { it.copy(phase = ModelSetupPhase.FAILED, failureReason = reason) }
    }
}
