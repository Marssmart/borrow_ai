package org.deer.borrowai

import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.FakeSpeakerService
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.model.ModelDownloadError
import org.deer.borrowai.data.service.model.SpeakerModelSetupPhase
import org.deer.borrowai.data.service.model.SpeakerModelSetupService
import org.deer.borrowai.data.service.model.SpeakerModelSetupState
import org.deer.borrowai.data.service.speaker.SpeakerAnalyzer
import org.deer.borrowai.data.service.speaker.SpeechTurn

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerSettingsTest {
    private class FakeAnalyzer(var available: Boolean) : SpeakerAnalyzer {
        override suspend fun isAvailable(): Boolean = available

        override suspend fun analyze(recordingPath: Path, shouldPause: () -> Boolean): List<SpeechTurn> = emptyList()
    }

    private class FakeSpeakerModelSetupService(
        private val analyzer: FakeAnalyzer,
        private val analyzerAvailableWhenReady: Boolean,
    ) : SpeakerModelSetupService {
        private val mutableState = MutableStateFlow(SpeakerModelSetupState())
        override val state: StateFlow<SpeakerModelSetupState> = mutableState

        override suspend fun prepare(): Either<ModelDownloadError, Unit> {
            mutableState.value = SpeakerModelSetupState(phase = SpeakerModelSetupPhase.READY)
            analyzer.available = analyzerAvailableWhenReady
            return Unit.right()
        }

        override suspend fun downloadOverMobileData() = Unit
    }

    private fun holder(
        speakers: FakeSpeakerService = FakeSpeakerService(),
        memories: MemoryService = FakeMemoryService(epochClock = EpochClock { 1_800_000_000_000L }),
        analyzer: FakeAnalyzer = FakeAnalyzer(available = false),
        analyzerAvailableWhenReady: Boolean = true,
        scope: kotlinx.coroutines.CoroutineScope,
    ): SpeakerSettingsStateHolder = SpeakerSettingsStateHolder(
        speakers = speakers,
        memories = memories,
        analyzer = analyzer,
        modelSetup = FakeSpeakerModelSetupService(analyzer, analyzerAvailableWhenReady),
        scope = scope,
    )

    @Test
    fun switchIsOffAndLockedUntilTheModelsAreAvailable() {
        val state = SpeakerSettingsUiState()

        assertFalse(state.enabled)
        assertFalse(state.switchEnabled)
    }

    @Test
    fun agreeingToConsentEnablesSpeakerAnalysis() = runTest {
        val speakers = FakeSpeakerService()
        val analyzer = FakeAnalyzer(available = true)
        val holder = holder(speakers = speakers, analyzer = analyzer, scope = backgroundScope)
        runCurrent()

        holder.turnOn()
        runCurrent()
        assertTrue(holder.uiState.value.consentDialogVisible)
        assertEquals(false, speakers.isFeatureEnabled().getOrNull())

        holder.agreeToConsent()
        runCurrent()

        assertEquals(true, speakers.hasCurrentConsent().getOrNull())
        assertEquals(true, speakers.isFeatureEnabled().getOrNull())
        assertFalse(holder.uiState.value.consentDialogVisible)
    }

    @Test
    fun decliningConsentLeavesTheFeatureOff() = runTest {
        val speakers = FakeSpeakerService()
        val holder = holder(speakers = speakers, analyzer = FakeAnalyzer(available = true), scope = backgroundScope)
        runCurrent()

        holder.turnOn()
        runCurrent()
        holder.declineConsent()

        assertEquals(false, speakers.isFeatureEnabled().getOrNull())
        assertEquals(false, speakers.hasCurrentConsent().getOrNull())
    }

    @Test
    fun turningOffKeepsConsentAndSpeakerData() = runTest {
        val speakers = FakeSpeakerService(enabled = true)
        val ana = speakers.createSpeaker("Ana").getOrNull()!!
        val holder = holder(speakers = speakers, analyzer = FakeAnalyzer(available = true), scope = backgroundScope)
        runCurrent()

        holder.turnOff()
        runCurrent()

        assertEquals(false, speakers.isFeatureEnabled().getOrNull())
        assertEquals(true, speakers.hasCurrentConsent().getOrNull())
        assertEquals(ana, speakers.findSpeaker(ana.id).getOrNull())
    }

    @Test
    fun withdrawalClearsConsentSpeakerDataAndAnalysisStatus() = runTest {
        val speakers = FakeSpeakerService(enabled = true)
        speakers.createSpeaker("Ana")
        val memory = Memory(
            id = "memory-1",
            createdAtEpochMillis = 1L,
            title = "Conversation",
            summary = "",
            transcription = "Hello",
            recordingPath = "memory-1.m4a",
            speakerStatus = SpeakerStatus.DONE,
            speakerAnalysisVersion = 1,
        )
        val memories = FakeMemoryService(
            initialMemories = listOf(memory),
            epochClock = EpochClock { 1_800_000_000_000L },
        )
        val holder = holder(
            speakers = speakers,
            memories = memories,
            analyzer = FakeAnalyzer(available = true),
            scope = backgroundScope,
        )
        runCurrent()

        holder.requestWithdrawal()
        holder.confirmWithdrawal()
        runCurrent()

        assertEquals(false, speakers.hasCurrentConsent().getOrNull())
        assertEquals(false, speakers.isFeatureEnabled().getOrNull())
        assertTrue(speakers.findSpeakers().getOrNull()!!.isEmpty())
        val reset = memories.findById(memory.id).getOrNull()!!
        assertEquals(SpeakerStatus.SKIPPED, reset.speakerStatus)
        assertEquals(null, reset.speakerAnalysisVersion)
    }

    @Test
    fun downloadingSpeakerModelsMakesTheSwitchAvailable() = runTest {
        val analyzer = FakeAnalyzer(available = false)
        val holder = holder(analyzer = analyzer, scope = backgroundScope)
        runCurrent()
        assertFalse(holder.uiState.value.switchEnabled)

        holder.downloadSpeakerModels()
        runCurrent()

        assertTrue(holder.uiState.value.modelAvailable)
        assertTrue(holder.uiState.value.switchEnabled)
    }

    @Test
    fun readyModelsDoNotEnableConsentWhenTheNativeRuntimeIsUnavailable() = runTest {
        val analyzer = FakeAnalyzer(available = false)
        val holder = holder(
            analyzer = analyzer,
            analyzerAvailableWhenReady = false,
            scope = backgroundScope,
        )
        runCurrent()

        holder.downloadSpeakerModels()
        runCurrent()

        assertEquals(SpeakerModelSetupPhase.READY, holder.uiState.value.modelSetup.phase)
        assertFalse(holder.uiState.value.modelAvailable)
        assertFalse(holder.uiState.value.switchEnabled)
    }
}
