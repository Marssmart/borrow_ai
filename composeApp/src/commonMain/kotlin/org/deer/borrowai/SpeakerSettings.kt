package org.deer.borrowai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.SpeakerService
import org.deer.borrowai.data.service.model.SpeakerModelSetupPhase
import org.deer.borrowai.data.service.model.SpeakerModelSetupService
import org.deer.borrowai.data.service.model.SpeakerModelSetupState
import org.deer.borrowai.data.service.speaker.SpeakerAnalyzer

internal data class SpeakerSettingsUiState(
    val enabled: Boolean = false,
    val consentAccepted: Boolean = false,
    val modelAvailable: Boolean = false,
    val modelSetup: SpeakerModelSetupState = SpeakerModelSetupState(),
    val consentDialogVisible: Boolean = false,
    val withdrawalDialogVisible: Boolean = false,
    val actionFailed: Boolean = false,
) {
    val switchEnabled: Boolean
        get() = enabled || modelAvailable

    val downloadInProgress: Boolean
        get() = modelSetup.phase == SpeakerModelSetupPhase.DOWNLOADING ||
            modelSetup.phase == SpeakerModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK
}

/** Reads the speaker setting, model availability, and explicit consent state for Settings. */
internal class SpeakerSettingsStateHolder(
    private val speakers: SpeakerService,
    private val memories: MemoryService,
    private val analyzer: SpeakerAnalyzer,
    private val modelSetup: SpeakerModelSetupService,
    private val scope: CoroutineScope,
) {
    private val mutableUiState = MutableStateFlow(SpeakerSettingsUiState())

    val uiState: StateFlow<SpeakerSettingsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            var available = analyzer.isAvailable()
            var lastPhase: SpeakerModelSetupPhase? = null
            combine(
                speakers.changes.onStart { emit(Unit) },
                modelSetup.state,
            ) { _, setup -> setup }.collect { setup ->
                if (setup.phase != lastPhase) {
                    available = when (setup.phase) {
                        SpeakerModelSetupPhase.READY -> analyzer.isAvailable()
                        SpeakerModelSetupPhase.UNSUPPORTED_DEVICE -> false
                        SpeakerModelSetupPhase.NOT_STARTED,
                        SpeakerModelSetupPhase.FAILED,
                        -> analyzer.isAvailable()
                        SpeakerModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK,
                        SpeakerModelSetupPhase.DOWNLOADING,
                        -> available
                    }
                    lastPhase = setup.phase
                }
                val enabled = speakers.isFeatureEnabled().getOrNull() ?: return@collect
                val consentAccepted = speakers.hasCurrentConsent().getOrNull() ?: return@collect
                mutableUiState.value = mutableUiState.value.copy(
                    enabled = enabled,
                    consentAccepted = consentAccepted,
                    modelAvailable = available,
                    modelSetup = setup,
                )
            }
        }
    }

    /** Opens the consent prompt before enabling any analysis. */
    fun turnOn() {
        scope.launch {
            if (!uiState.value.modelAvailable) {
                mutableUiState.value = mutableUiState.value.copy(actionFailed = true)
                return@launch
            }
            val hasConsent = speakers.hasCurrentConsent().getOrNull()
            when (hasConsent) {
                null -> mutableUiState.value = mutableUiState.value.copy(actionFailed = true)
                false -> mutableUiState.value = mutableUiState.value.copy(
                    consentDialogVisible = true,
                    actionFailed = false,
                )
                true -> speakers.setFeatureEnabled(true).fold(
                    ifLeft = { mutableUiState.value = mutableUiState.value.copy(actionFailed = true) },
                    ifRight = { mutableUiState.value = mutableUiState.value.copy(actionFailed = false) },
                )
            }
        }
    }

    fun declineConsent() {
        mutableUiState.value = mutableUiState.value.copy(consentDialogVisible = false)
    }

    fun agreeToConsent() {
        scope.launch {
            speakers.acceptConsent().fold(
                ifLeft = {
                    mutableUiState.value = mutableUiState.value.copy(
                        consentDialogVisible = false,
                        actionFailed = true,
                    )
                },
                ifRight = {
                    mutableUiState.value = mutableUiState.value.copy(
                        consentDialogVisible = false,
                        actionFailed = false,
                    )
                },
            )
        }
    }

    /** Stops analysis without deleting the speaker library (AC-6.3). */
    fun turnOff() {
        scope.launch {
            speakers.setFeatureEnabled(false).fold(
                ifLeft = { mutableUiState.value = mutableUiState.value.copy(actionFailed = true) },
                ifRight = { mutableUiState.value = mutableUiState.value.copy(actionFailed = false) },
            )
        }
    }

    fun downloadSpeakerModels() {
        scope.launch {
            mutableUiState.value = mutableUiState.value.copy(actionFailed = false)
            modelSetup.prepare().fold(
                ifLeft = { mutableUiState.value = mutableUiState.value.copy(actionFailed = true) },
                ifRight = { mutableUiState.value = mutableUiState.value.copy(actionFailed = false) },
            )
        }
    }

    fun downloadOverMobileData() {
        scope.launch {
            modelSetup.downloadOverMobileData()
        }
    }

    fun requestWithdrawal() {
        mutableUiState.value = mutableUiState.value.copy(withdrawalDialogVisible = true)
    }

    fun cancelWithdrawal() {
        mutableUiState.value = mutableUiState.value.copy(withdrawalDialogVisible = false)
    }

    /** Withdrawal disables analysis, clears all speaker documents and resets the memory status fields. */
    fun confirmWithdrawal() {
        scope.launch {
            speakers.clearAll().fold(
                ifLeft = {
                    mutableUiState.value = mutableUiState.value.copy(
                        withdrawalDialogVisible = false,
                        actionFailed = true,
                    )
                },
                ifRight = {
                    memories.resetSpeakerAnalyses().fold(
                        ifLeft = {
                            mutableUiState.value = mutableUiState.value.copy(
                                withdrawalDialogVisible = false,
                                actionFailed = true,
                            )
                        },
                        ifRight = {
                            mutableUiState.value = mutableUiState.value.copy(
                                withdrawalDialogVisible = false,
                                actionFailed = false,
                            )
                        },
                    )
                },
            )
        }
    }
}
