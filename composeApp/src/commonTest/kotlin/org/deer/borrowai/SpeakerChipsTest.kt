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
import org.deer.borrowai.data.domain.SPEAKER_PALETTE_SIZE
import org.deer.borrowai.data.domain.Speaker
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.domain.newSpeakerColorIndex
import org.deer.borrowai.data.service.FakeSpeakerService
import org.deer.borrowai.data.service.SpeakerError
import org.deer.borrowai.data.service.SpeakerService

class SpeakerChipsTest {
    @Test
    fun matchedOccurrenceShowsTheLibraryNameInTheSpeakersColour() {
        val state = speakerChipsState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(1, speakerId = "priya")),
            speakersById = mapOf("priya" to speaker("priya", "Priya", colorIndex = 3)),
            analysisActive = false,
        )

        assertEquals(SpeakerChipsState.Speakers(listOf(SpeakerChip.Recognized(1, 3, "Priya")), overflow = 0), state)
    }

    @Test
    fun unmatchedOccurrenceShowsSpeakerNumberInTheFirstFreeColour() {
        val state = speakerChipsState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(2, speakerId = null)),
            speakersById = emptyMap(),
            analysisActive = false,
        )

        assertEquals(SpeakerChipsState.Speakers(listOf(SpeakerChip.Unknown(2, 0)), overflow = 0), state)
    }

    @Test
    fun unknownSpeakerSkipsTheColourOfALibrarySpeakerInTheSameMemory() {
        val state = speakerChipsState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(1, speakerId = null), occurrence(2, speakerId = "priya")),
            speakersById = mapOf("priya" to speaker("priya", "Priya", colorIndex = 0)),
            analysisActive = false,
        )

        assertEquals(
            SpeakerChipsState.Speakers(
                listOf(SpeakerChip.Unknown(1, 1), SpeakerChip.Recognized(2, 0, "Priya")),
                overflow = 0,
            ),
            state,
        )
    }

    @Test
    fun twoUnknownSpeakersInOneMemoryGetDifferentColours() {
        val state = speakerChipsState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(1, speakerId = null), occurrence(2, speakerId = null)),
            speakersById = emptyMap(),
            analysisActive = false,
        )

        assertEquals(listOf(0, 1), (state as SpeakerChipsState.Speakers).chips.map { it.colorIndex })
    }

    @Test
    fun moreThanThreeSpeakersShowThreeChipsAndCountTheRest() {
        val state = speakerChipsState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = (1..5).map { occurrence(it, speakerId = null) },
            speakersById = emptyMap(),
            analysisActive = false,
        ) as SpeakerChipsState.Speakers

        assertEquals(listOf(1, 2, 3), state.chips.map { it.ordinal })
        assertEquals(2, state.overflow)
    }

    @Test
    fun analysisInProgressShowsAPendingChipOnlyWhileItCanRun() {
        for (status in listOf(SpeakerStatus.PENDING, SpeakerStatus.ANALYZING)) {
            assertEquals(
                SpeakerChipsState.Pending,
                speakerChipsState(memory(status), emptyList(), emptyMap(), analysisActive = true),
            )
            assertNull(speakerChipsState(memory(status), emptyList(), emptyMap(), analysisActive = false))
        }
    }

    @Test
    fun memoryWithNoSpeakersAndNoAnalysisInProgressShowsNoChips() {
        for (status in listOf(SpeakerStatus.DONE, SpeakerStatus.SKIPPED, SpeakerStatus.FAILED)) {
            assertNull(speakerChipsState(memory(status), emptyList(), emptyMap(), analysisActive = true))
        }
    }

    @Test
    fun occurrenceWhoseLibrarySpeakerIsMissingShowsAsUnknown() {
        val state = speakerChipsState(
            memory = memory(SpeakerStatus.DONE),
            occurrences = listOf(occurrence(1, speakerId = "gone")),
            speakersById = emptyMap(),
            analysisActive = false,
        )

        assertEquals(SpeakerChipsState.Speakers(listOf(SpeakerChip.Unknown(1, 0)), overflow = 0), state)
    }

    @Test
    fun newSpeakerTakesTheFirstColourNoLibrarySpeakerUses() {
        assertEquals(0, newSpeakerColorIndex(emptyList()))
        assertEquals(1, newSpeakerColorIndex(listOf(speaker("a", "A", 0), speaker("b", "B", 2))))
    }

    @Test
    fun fullPaletteWrapsAroundForANewSpeaker() {
        val full = (0 until SPEAKER_PALETTE_SIZE).map { speaker("s$it", "S$it", colorIndex = it) }

        assertEquals(0, newSpeakerColorIndex(full))
    }

    @Test
    fun everyPaletteColourPassesAaWithWhiteText() {
        for (argb in speakerPalette) {
            assertTrue(contrastWithWhite(argb) >= 4.5, "Palette colour ${argb.toString(16)} fails 4.5:1")
        }
    }

    @Test
    fun loadReadsOccurrencesOnceForAnyNumberOfMemories() = runTest {
        val fake = FakeSpeakerService(enabled = true)
        fake.replaceOccurrences("m1", SPEAKER_ANALYSIS_VERSION, listOf(draft(1, speakerId = null)))
        fake.replaceOccurrences("m2", SPEAKER_ANALYSIS_VERSION, listOf(draft(1, speakerId = null), draft(2, speakerId = null)))
        val counting = CountingSpeakerService(fake)
        val memories = listOf(memory(SpeakerStatus.DONE, "m1"), memory(SpeakerStatus.DONE, "m2"), memory(SpeakerStatus.DONE, "m3"))

        val chips = loadSpeakerChips(counting, memories, analysisActive = false)

        assertEquals(setOf("m1", "m2"), chips.keys)
        assertEquals(1, counting.occurrenceReads)
        assertEquals(0, counting.speakerReads)
    }

    @Test
    fun loadReadsTheLibraryOnceWhenAMatchIsShown() = runTest {
        val fake = FakeSpeakerService(enabled = true)
        val priya = fake.createSpeaker("Priya").getOrNull()!!
        fake.replaceOccurrences("m1", SPEAKER_ANALYSIS_VERSION, listOf(draft(1, speakerId = priya.id)))
        val counting = CountingSpeakerService(fake)

        val chips = loadSpeakerChips(counting, listOf(memory(SpeakerStatus.DONE)), analysisActive = false)

        assertEquals(
            SpeakerChipsState.Speakers(listOf(SpeakerChip.Recognized(1, priya.colorIndex, "Priya")), overflow = 0),
            chips["m1"],
        )
        assertEquals(1, counting.occurrenceReads)
        assertEquals(1, counting.speakerReads)
    }

    @Test
    fun loadShowsNoChipsWhenOccurrencesCannotBeRead() = runTest {
        val failing = object : SpeakerService by FakeSpeakerService(enabled = true) {
            override suspend fun findAllOccurrences(): Either<SpeakerError, List<SpeakerOccurrence>> =
                SpeakerError.Persistence(IllegalStateException("store unavailable")).left()
        }

        val chips = loadSpeakerChips(failing, listOf(memory(SpeakerStatus.PENDING)), analysisActive = true)

        assertEquals(emptyMap<String, SpeakerChipsState>(), chips)
    }

    @Test
    fun loadShowsNoChipsWhenNamesCannotBeRead() = runTest {
        val fake = FakeSpeakerService(enabled = true)
        val priya = fake.createSpeaker("Priya").getOrNull()!!
        fake.replaceOccurrences("m1", SPEAKER_ANALYSIS_VERSION, listOf(draft(1, speakerId = priya.id)))
        val failingNames = object : SpeakerService by fake {
            override suspend fun findSpeakers(): Either<SpeakerError, List<Speaker>> =
                SpeakerError.Persistence(IllegalStateException("store unavailable")).left()
        }

        val chips = loadSpeakerChips(failingNames, listOf(memory(SpeakerStatus.DONE)), analysisActive = false)

        assertEquals(emptyMap<String, SpeakerChipsState>(), chips)
    }

    /** Counts the two reads that the chips use, so a test can check how many a page costs. */
    private class CountingSpeakerService(private val delegate: SpeakerService) : SpeakerService by delegate {
        var occurrenceReads = 0
            private set
        var speakerReads = 0
            private set

        override suspend fun findAllOccurrences(): Either<SpeakerError, List<SpeakerOccurrence>> {
            occurrenceReads++
            return delegate.findAllOccurrences()
        }

        override suspend fun findSpeakers(): Either<SpeakerError, List<Speaker>> {
            speakerReads++
            return delegate.findSpeakers()
        }
    }

    private fun memory(status: SpeakerStatus, id: String = "m1") = Memory(
        id = id,
        createdAtEpochMillis = 0L,
        title = id,
        summary = "",
        transcription = "",
        speakerStatus = status,
    )

    private fun speaker(id: String, name: String, colorIndex: Int) = Speaker(
        id = id,
        name = name,
        createdAtEpochMillis = 0L,
        lastUsedAtEpochMillis = 0L,
        embeddingCount = 0,
        colorIndex = colorIndex,
    )

    private fun occurrence(ordinal: Int, speakerId: String?) = SpeakerOccurrence(
        id = "occurrence-$ordinal-${speakerId ?: "unknown"}",
        memoryId = "m1",
        ordinal = ordinal,
        speakerId = speakerId,
        link = if (speakerId == null) SpeakerLink.NONE else SpeakerLink.AUTO,
        rejectedSpeakerIds = emptyList(),
        totalSpeechMs = 1_000L,
        sampleStartMs = 0L,
        sampleEndMs = 1_000L,
        embeddingId = "embedding-$ordinal",
    )

    private fun draft(ordinal: Int, speakerId: String?) = OccurrenceDraft(
        ordinal = ordinal,
        speakerId = speakerId,
        link = if (speakerId == null) SpeakerLink.NONE else SpeakerLink.AUTO,
        totalSpeechMs = 1_000L,
        sampleStartMs = 0L,
        sampleEndMs = 1_000L,
        centroid = floatArrayOf(1f, 0f),
    )
}
