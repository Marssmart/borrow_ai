package org.deer.borrowai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.FakeSpeakerService
import org.deer.borrowai.data.service.speaker.SpeakerAnalyzer
import org.deer.borrowai.data.service.speaker.SpeechTurn

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerSettingsTest {
    private class FakeAnalyzer(private val available: Boolean) : SpeakerAnalyzer {
        override suspend fun isAvailable(): Boolean = available

        override suspend fun analyze(recordingPath: Path): List<SpeechTurn> = emptyList()
    }

    @Test
    fun switchIsOffAndLockedWhileTheFeatureIsOff() {
        val state = SpeakerSettingsUiState()

        assertFalse(state.enabled)
        assertFalse(state.switchEnabled)
    }

    @Test
    fun switchStaysLockedWhenModelsAreAvailableBecauseConsentIsNotBuiltYet() {
        val state = SpeakerSettingsUiState(enabled = false, modelAvailable = true)

        assertFalse(state.switchEnabled)
    }

    @Test
    fun storedOnSwitchCanBeTurnedOff() {
        assertTrue(SpeakerSettingsUiState(enabled = true, modelAvailable = false).switchEnabled)
    }

    @Test
    fun holderReportsNoModelWhileNoModelIsPinned() = runTest {
        val holder = SpeakerSettingsStateHolder(FakeSpeakerService(), FakeAnalyzer(available = false), backgroundScope)
        runCurrent()

        assertEquals(SpeakerSettingsUiState(enabled = false, modelAvailable = false), holder.uiState.value)
    }

    @Test
    fun holderReadsTheStoredSettingAndModelAvailability() = runTest {
        val holder = SpeakerSettingsStateHolder(FakeSpeakerService(enabled = true), FakeAnalyzer(available = true), backgroundScope)
        runCurrent()

        assertEquals(SpeakerSettingsUiState(enabled = true, modelAvailable = true), holder.uiState.value)
    }

    @Test
    fun turningOffWritesThroughAndUpdatesTheRows() = runTest {
        val speakers = FakeSpeakerService(enabled = true)
        val holder = SpeakerSettingsStateHolder(speakers, FakeAnalyzer(available = false), backgroundScope)
        runCurrent()

        holder.turnOff()
        runCurrent()

        assertEquals(false, speakers.isFeatureEnabled().getOrNull())
        assertFalse(holder.uiState.value.enabled)
    }
}
