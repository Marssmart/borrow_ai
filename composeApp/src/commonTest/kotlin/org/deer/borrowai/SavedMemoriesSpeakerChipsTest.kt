package org.deer.borrowai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.SPEAKER_ANALYSIS_VERSION
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.FakeSpeakerService
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.SpeakerService

class SavedMemoriesSpeakerChipsTest {
    @Test
    fun chipsFollowTheSpeakerStoreWithoutARestart() = runTest {
        val speakers = FakeSpeakerService(enabled = true)
        val holder = holder(memoryService(memory("m1")), speakers)
        runCurrent()
        assertNull(holder.uiState.value.speakerChips["m1"])

        speakers.replaceOccurrences("m1", SPEAKER_ANALYSIS_VERSION, listOf(unknownDraft(1)))
        runCurrent()
        assertEquals(
            SpeakerChipsState.Speakers(listOf(SpeakerChip.Unknown(ordinal = 1, colorIndex = 0)), overflow = 0),
            holder.uiState.value.speakerChips["m1"],
        )

        speakers.deleteForMemory("m1")
        runCurrent()
        assertNull(holder.uiState.value.speakerChips["m1"])
    }

    @Test
    fun pendingChipIsLeftOutWhileAnalysisCannotRun() = runTest {
        val holder = holder(
            memoryService(memory("m1", SpeakerStatus.PENDING)),
            FakeSpeakerService(enabled = true),
            analysisActive = false,
        )
        runCurrent()

        assertNull(holder.uiState.value.speakerChips["m1"])
    }

    private fun memory(id: String, status: SpeakerStatus = SpeakerStatus.DONE) = Memory(
        id = id,
        createdAtEpochMillis = 0L,
        title = id,
        summary = "",
        transcription = "",
        speakerStatus = status,
    )

    private fun memoryService(vararg memories: Memory): MemoryService = FakeMemoryService(
        initialMemories = memories.toList(),
        epochClock = EpochClock { 0L },
    )

    private fun unknownDraft(ordinal: Int) = OccurrenceDraft(
        ordinal = ordinal,
        speakerId = null,
        link = SpeakerLink.NONE,
        totalSpeechMs = 1_000L,
        sampleStartMs = 0L,
        sampleEndMs = 1_000L,
        centroid = floatArrayOf(1f, 0f),
    )

    private fun TestScope.holder(
        memoryService: MemoryService,
        speakers: SpeakerService,
        analysisActive: Boolean = false,
    ) = SavedMemoriesStateHolder(
        memoryService = memoryService,
        mediaSessionService = FakeMediaSession(),
        speakers = speakers,
        speakerAnalysisActive = { analysisActive },
        summaryModelMissing = MutableStateFlow(false),
        pagerState = PagerState(pageCount = { 2 }),
        memoriesListState = LazyListState(),
        detailScrollState = ScrollState(0),
        scope = backgroundScope,
        launchWrite = { block -> launch { block() } },
    )
}
