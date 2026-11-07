package org.deer.borrowai.data.service.speaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrence
import org.deer.borrowai.data.domain.SpeakerSampleRange

class SpeakerAnalysisPlannerTest {
    private fun turn(startMs: Long, endMs: Long, vararg values: Float) =
        SpeechTurn(startMs, endMs, floatArrayOf(*values))

    private fun libraryEntry(speakerId: String, vararg values: Float) = SpeakerEmbedding(
        id = "library-$speakerId",
        memoryId = "older-memory",
        speakerId = speakerId,
        vector = floatArrayOf(*values),
        modelVersion = 1,
        sourceOccurrenceId = "older-occurrence",
        createdAtEpochMillis = 0L,
    )

    @Test
    fun knownVoiceIsLinkedAutomatically() {
        val drafts = planSpeakerOccurrences(
            turns = listOf(turn(0, 3_000, 1f, 0f, 0f, 0f)),
            library = listOf(libraryEntry("alice", 1f, 0f, 0f, 0f)),
            modelVersion = 1,
            knownSpeakerIds = setOf("alice"),
        )

        val draft = drafts.single()
        assertEquals("alice", draft.speakerId)
        assertEquals(SpeakerLink.AUTO, draft.link)
        assertEquals(1, draft.ordinal)
        assertEquals(3_000L, draft.totalSpeechMs)
        assertEquals(0L, draft.sampleStartMs)
        assertEquals(3_000L, draft.sampleEndMs)
    }

    @Test
    fun repeatedSpeakerTurnsStayOneOccurrenceWithSeveralSamples() {
        val drafts = planSpeakerOccurrences(
            turns = listOf(
                turn(0, 5_000, 1f, 0f, 0f, 0f),
                turn(5_000, 10_000, 0f, 1f, 0f, 0f),
                turn(10_000, 15_000, 1f, 0f, 0f, 0f),
                turn(15_000, 20_000, 0f, 1f, 0f, 0f),
                turn(20_000, 25_000, 1f, 0f, 0f, 0f),
            ),
            library = emptyList(),
            modelVersion = 1,
            knownSpeakerIds = emptySet(),
        )

        assertEquals(2, drafts.size)
        assertEquals(
            listOf(
                SpeakerSampleRange(0, 5_000),
                SpeakerSampleRange(10_000, 15_000),
                SpeakerSampleRange(20_000, 25_000),
            ),
            drafts.first().sampleRanges,
        )
        assertEquals(SpeakerSampleRange(0, 5_000), drafts.first().sampleRanges.first())
    }

    @Test
    fun unknownVoiceStaysUnlinked() {
        val drafts = planSpeakerOccurrences(
            turns = listOf(turn(0, 3_000, 0f, 1f, 0f, 0f)),
            library = listOf(libraryEntry("alice", 1f, 0f, 0f, 0f)),
            modelVersion = 1,
            knownSpeakerIds = setOf("alice"),
        )

        assertEquals(SpeakerLink.NONE, drafts.single().link)
        assertEquals(null, drafts.single().speakerId)
    }

    @Test
    fun noSpeechGivesNoDrafts() {
        assertTrue(planSpeakerOccurrences(emptyList(), emptyList(), modelVersion = 1, knownSpeakerIds = emptySet()).isEmpty())
    }

    @Test
    fun speakersWithOnlyOverlappedSpeechAreDropped() {
        val drafts = planSpeakerOccurrences(
            turns = listOf(turn(0, 2_000, 1f, 0f, 0f, 0f), turn(0, 2_000, 0f, 1f, 0f, 0f)),
            library = emptyList(),
            modelVersion = 1,
            knownSpeakerIds = emptySet(),
        )

        assertTrue(drafts.isEmpty())
    }

    @Test
    fun aSpeakerLinksAtMostOnceInOneMemory() {
        val drafts = planSpeakerOccurrences(
            turns = listOf(
                turn(0, 3_000, 0.766f, 0.643f, 0f, 0f),
                turn(3_000, 6_000, 0.766f, -0.643f, 0f, 0f),
            ),
            library = listOf(libraryEntry("alice", 1f, 0f, 0f, 0f)),
            modelVersion = 1,
            knownSpeakerIds = setOf("alice"),
        )

        val linked = drafts.filter { it.speakerId == "alice" }
        assertEquals(2, drafts.size)
        assertEquals(1, linked.size)
        assertEquals(1, linked.single().ordinal)
        assertEquals(SpeakerLink.NONE, drafts.single { it.ordinal == 2 }.link)
    }

    @Test
    fun manualLinkSurvivesARebuild() {
        val previous = SpeakerOccurrence(
            id = "old",
            memoryId = "memory",
            ordinal = 1,
            speakerId = "bob",
            link = SpeakerLink.MANUAL,
            rejectedSpeakerIds = emptyList(),
            totalSpeechMs = 3_000L,
            sampleStartMs = 0L,
            sampleEndMs = 3_000L,
            embeddingId = "old-embedding",
        )

        val drafts = planSpeakerOccurrences(
            turns = listOf(turn(0, 3_000, 0f, 1f, 0f, 0f)),
            library = emptyList(),
            modelVersion = 1,
            knownSpeakerIds = setOf("bob"),
            previous = listOf(previous),
        )

        assertEquals("bob", drafts.single().speakerId)
        assertEquals(SpeakerLink.MANUAL, drafts.single().link)
    }

    @Test
    fun rejectionsCarryOverToTheRebuild() {
        val previous = SpeakerOccurrence(
            id = "old",
            memoryId = "memory",
            ordinal = 1,
            speakerId = null,
            link = SpeakerLink.NONE,
            rejectedSpeakerIds = listOf("alice"),
            totalSpeechMs = 3_000L,
            sampleStartMs = 0L,
            sampleEndMs = 3_000L,
            embeddingId = "old-embedding",
        )

        val drafts = planSpeakerOccurrences(
            turns = listOf(turn(0, 3_000, 1f, 0f, 0f, 0f)),
            library = listOf(libraryEntry("alice", 1f, 0f, 0f, 0f)),
            modelVersion = 1,
            knownSpeakerIds = setOf("alice"),
            previous = listOf(previous),
        )

        assertEquals(SpeakerLink.NONE, drafts.single().link)
        assertEquals(listOf("alice"), drafts.single().rejectedSpeakerIds)
    }

    @Test
    fun invalidTurnIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            planSpeakerOccurrences(listOf(turn(2_000, 1_000, 1f, 0f)), emptyList(), modelVersion = 1, knownSpeakerIds = emptySet())
        }
    }
}
