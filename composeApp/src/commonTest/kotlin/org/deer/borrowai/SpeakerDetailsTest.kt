package org.deer.borrowai

import arrow.core.Either
import arrow.core.left
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.SPEAKER_ANALYSIS_VERSION
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.service.FakeSpeakerService
import org.deer.borrowai.data.service.SpeakerError
import org.deer.borrowai.data.service.SpeakerService

class SpeakerDetailsTest {
    private fun memory(status: SpeakerStatus, recordingPath: String? = "recordings/m1.m4a") = Memory(
        id = "m1",
        createdAtEpochMillis = 0L,
        title = "",
        summary = "",
        transcription = "",
        recordingPath = recordingPath,
        speakerStatus = status,
    )

    private fun occurrence(
        ordinal: Int,
        speakerId: String? = null,
        speechMs: Long = 2_000L,
        startMs: Long = 0L,
        endMs: Long = 4_000L,
    ) = SpeakerOccurrence(
        id = "o$ordinal",
        memoryId = "m1",
        ordinal = ordinal,
        speakerId = speakerId,
        link = if (speakerId == null) SpeakerLink.NONE else SpeakerLink.AUTO,
        rejectedSpeakerIds = emptyList(),
        totalSpeechMs = speechMs,
        sampleStartMs = startMs,
        sampleEndMs = endMs,
        embeddingId = "e$ordinal",
    )

    private fun sample(startMs: Long, endMs: Long) = SpeakerSample(memoryId = "m1", startMs = startMs, endMs = endMs)

    @Test
    fun unknownSpeakersAreLabelledByOrdinalInOrder() {
        val state = speakersSectionState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(2), occurrence(1)),
            namesById = emptyMap(),
            audioAvailable = true,
        )

        assertEquals(
            listOf(SpeakerLabel.Unknown(1), SpeakerLabel.Unknown(2)),
            (state as SpeakersSectionState.Cards).cards.map { it.label },
        )
    }

    @Test
    fun matchedSpeakerShowsItsLibraryName() {
        val state = speakersSectionState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(1, speakerId = "priya")),
            namesById = mapOf("priya" to "Priya"),
            audioAvailable = true,
        )

        assertEquals(SpeakerLabel.Named("Priya"), (state as SpeakersSectionState.Cards).cards.single().label)
    }

    @Test
    fun matchedSpeakerMissingFromTheLibraryFallsBackToOrdinal() {
        val state = speakersSectionState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(3, speakerId = "gone")),
            namesById = emptyMap(),
            audioAvailable = true,
        )

        assertEquals(SpeakerLabel.Unknown(3), (state as SpeakersSectionState.Cards).cards.single().label)
    }

    @Test
    fun playableCardPlaysOnlyItsOwnSampleRange() {
        val state = speakersSectionState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(1, startMs = 6_000L, endMs = 9_500L)),
            namesById = emptyMap(),
            audioAvailable = true,
        )

        assertEquals(
            SpeakerAudio.Playable(sample(startMs = 6_000L, endMs = 9_500L)),
            (state as SpeakersSectionState.Cards).cards.single().audio,
        )
    }

    @Test
    fun cardWithMissingRecordingIsUnavailableAndHasNothingToPlay() {
        val state = speakersSectionState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(1, speechMs = 4_000L)),
            namesById = emptyMap(),
            audioAvailable = false,
        )

        val card = (state as SpeakersSectionState.Cards).cards.single()
        assertEquals(SpeakerAudio.Unavailable, card.audio)
        assertEquals(4_000L, card.speechMillis)
    }

    @Test
    fun finishedAnalysisWithoutSpeakersSaysNoSpeakersWereDetected() {
        val state = speakersSectionState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = emptyList(),
            namesById = emptyMap(),
            audioAvailable = true,
        )

        assertEquals(SpeakersSectionState.NoneDetected, state)
    }

    @Test
    fun memoriesWithoutFinishedAnalysisShowNoSectionWhenTheyHaveNoSpeakers() {
        listOf(SpeakerStatus.PENDING, SpeakerStatus.ANALYZING, SpeakerStatus.FAILED, SpeakerStatus.SKIPPED).forEach { status ->
            assertNull(
                speakersSectionState(
                    memory = memory(status),
                    occurrences = emptyList(),
                    namesById = emptyMap(),
                    audioAvailable = true,
                ),
                status.name,
            )
        }
    }

    @Test
    fun speechTimeIsShownAsMinutesAndSeconds() {
        assertEquals("0:04", formatSpeechDuration(4_000L))
        assertEquals("2:10", formatSpeechDuration(130_000L))
        assertEquals("0:59", formatSpeechDuration(59_999L))
        assertEquals("60:00", formatSpeechDuration(3_600_000L))
    }

    @Test
    fun loaderReadsOccurrencesWithTheirSpeakerNames() = runTest {
        val speakers = FakeSpeakerService(enabled = true)
        val priya = speakers.createSpeaker("Priya").getOrNull()!!
        val written = speakers.replaceOccurrences(
            memoryId = "m1",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(
                OccurrenceDraft(
                    ordinal = 1,
                    speakerId = priya.id,
                    link = SpeakerLink.AUTO,
                    totalSpeechMs = 130_000L,
                    sampleStartMs = 1_000L,
                    sampleEndMs = 5_000L,
                    centroid = floatArrayOf(1f, 0f),
                ),
                OccurrenceDraft(
                    ordinal = 2,
                    speakerId = null,
                    link = SpeakerLink.NONE,
                    totalSpeechMs = 4_000L,
                    sampleStartMs = 6_000L,
                    sampleEndMs = 9_000L,
                    centroid = floatArrayOf(0f, 1f),
                ),
            ),
        )
        assertTrue(written.isRight())

        val section = loadSpeakersSection(speakers, memory(SpeakerStatus.DONE), audioAvailable = true)

        assertEquals(
            SpeakersSectionState.Cards(
                listOf(
                    SpeakerCard(SpeakerLabel.Named("Priya"), 130_000L, SpeakerAudio.Playable(sample(1_000L, 5_000L))),
                    SpeakerCard(SpeakerLabel.Unknown(2), 4_000L, SpeakerAudio.Playable(sample(6_000L, 9_000L))),
                ),
            ),
            section,
        )
    }

    @Test
    fun loaderLeavesTheSectionOutWhenOccurrencesCannotBeRead() = runTest {
        val failing = object : SpeakerService by FakeSpeakerService(enabled = true) {
            override suspend fun findOccurrences(memoryId: String): Either<SpeakerError, List<SpeakerOccurrence>> =
                SpeakerError.Persistence(IllegalStateException("store unavailable")).left()
        }

        assertNull(loadSpeakersSection(failing, memory(SpeakerStatus.DONE), audioAvailable = true))
    }

    @Test
    fun loaderLeavesTheSectionOutWhenNamesCannotBeReadInsteadOfShowingOrdinals() = runTest {
        val speakers = FakeSpeakerService(enabled = true)
        val priya = speakers.createSpeaker("Priya").getOrNull()!!
        speakers.replaceOccurrences(
            memoryId = "m1",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(
                OccurrenceDraft(
                    ordinal = 1,
                    speakerId = priya.id,
                    link = SpeakerLink.AUTO,
                    totalSpeechMs = 4_000L,
                    sampleStartMs = 0L,
                    sampleEndMs = 4_000L,
                    centroid = floatArrayOf(1f),
                ),
            ),
        )
        val failingNames = object : SpeakerService by speakers {
            override suspend fun findSpeakers(): Either<SpeakerError, List<Speaker>> =
                SpeakerError.Persistence(IllegalStateException("store unavailable")).left()
        }

        assertNull(loadSpeakersSection(failingNames, memory(SpeakerStatus.DONE), audioAvailable = true))
    }
}
