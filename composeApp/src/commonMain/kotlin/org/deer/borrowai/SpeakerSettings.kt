package org.deer.borrowai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.deer.borrowai.data.service.SpeakerService
import org.deer.borrowai.data.service.speaker.SpeakerAnalyzer

/** The speaker recognition rows in Settings (US-6). [enabled] is the stored setting. */
internal data class SpeakerSettingsUiState(
    val enabled: Boolean = false,
    val modelAvailable: Boolean = false,
) {
    /**
     * The switch can only turn the feature off. Turning it on waits for the consent screen (section 9.8), which is not
     * built yet, so no analysis can start without consent.
     */
    val switchEnabled: Boolean
        get() = enabled
}

/** Reads the speaker recognition setting for Settings, and turns it off when the user asks. */
internal class SpeakerSettingsStateHolder(
    private val speakers: SpeakerService,
    private val analyzer: SpeakerAnalyzer,
    private val scope: CoroutineScope,
) {
    private val mutableUiState = MutableStateFlow(SpeakerSettingsUiState())

    val uiState: StateFlow<SpeakerSettingsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            val modelAvailable = analyzer.isAvailable()
            speakers.changes.onStart { emit(Unit) }.collect {
                val enabled = speakers.isFeatureEnabled().getOrNull() ?: return@collect
                mutableUiState.value = SpeakerSettingsUiState(enabled = enabled, modelAvailable = modelAvailable)
            }
        }
    }

    /** Stops speaker analysis. Existing speakers stay until the user deletes them (AC-6.3). */
    fun turnOff() {
        scope.launch { speakers.setFeatureEnabled(false) }
    }
}
