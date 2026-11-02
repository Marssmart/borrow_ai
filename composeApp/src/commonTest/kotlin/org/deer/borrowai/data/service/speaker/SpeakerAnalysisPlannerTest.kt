package org.deer.borrowai.data.service.speaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink

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
    fun unknownVoiceStaysUnlinked() {
        val drafts = planSpeakerOccurrences(
            turns = listOf(turn(0, 3_000, 0f, 1f, 0f, 0f)),
            library = listOf(libraryEntry("alice", 1f, 0f, 0f, 0f)),
            modelVersion = 1,
        )

        assertEquals(SpeakerLink.NONE, drafts.single().link)
        assertEquals(null, drafts.single().speakerId)
    }

    @Test
    fun noSpeechGivesNoDrafts() {
        assertTrue(planSpeakerOccurrences(emptyList(), emptyList(), modelVersion = 1).isEmpty())
    }

    @Test
    fun speakersWithOnlyOverlappedSpeechAreDropped() {
        val drafts = planSpeakerOccurrences(
            turns = listOf(turn(0, 2_000, 1f, 0f, 0f, 0f), turn(0, 2_000, 0f, 1f, 0f, 0f)),
            library = emptyList(),
            modelVersion = 1,
        )

        assertTrue(drafts.isEmpty())
    }

    @Test
    fun invalidTurnIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            planSpeakerOccurrences(listOf(turn(2_000, 1_000, 1f, 0f)), emptyList(), modelVersion = 1)
        }
    }
}
