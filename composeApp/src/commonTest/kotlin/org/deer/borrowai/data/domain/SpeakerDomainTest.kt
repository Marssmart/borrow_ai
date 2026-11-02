package org.deer.borrowai.data.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpeakerDomainTest {
    private fun memory(recordingPath: String? = "recordings/a.m4a") = Memory(
        id = "a",
        createdAtEpochMillis = 1L,
        title = "t",
        summary = "s",
        transcription = "x",
        recordingPath = recordingPath,
    )

    @Test
    fun memoryDefaultsToPendingWithNoSpeakerAttempts() {
        val memory = memory()

        assertEquals(SpeakerStatus.PENDING, memory.speakerStatus)
        assertEquals(0, memory.speakerAttempts)
        assertNull(memory.lastSpeakerError)
        assertNull(memory.speakerAnalysisVersion)
    }

    @Test
    fun memoryWithoutAudioStartsSkipped() {
        assertEquals(SpeakerStatus.SKIPPED, speakerStatusFor(null))
        assertEquals(SpeakerStatus.PENDING, speakerStatusFor("recordings/a.m4a"))
    }

    @Test
    fun claimCountsAnAttemptAndTheLastFailureIsFinal() {
        var current = memory().claimedForSpeakerAnalysis()
        assertEquals(SpeakerStatus.ANALYZING, current.speakerStatus)
        assertEquals(1, current.speakerAttempts)

        current = current.withFailedSpeakerAnalysis("first")
        assertEquals(SpeakerStatus.PENDING, current.speakerStatus)

        current = current.claimedForSpeakerAnalysis().withFailedSpeakerAnalysis("second")
        assertEquals(SpeakerStatus.PENDING, current.speakerStatus)

        current = current.claimedForSpeakerAnalysis().withFailedSpeakerAnalysis("third")
        assertEquals(SpeakerStatus.FAILED, current.speakerStatus)
        assertEquals(MAX_SPEAKER_ATTEMPTS, current.speakerAttempts)
        assertEquals("third", current.lastSpeakerError)
    }

    @Test
    fun interruptedClaimIsRequeuedWithoutRefundingItsAttempt() {
        val requeued = memory().claimedForSpeakerAnalysis().requeuedForSpeakerAnalysis()

        assertEquals(SpeakerStatus.PENDING, requeued.speakerStatus)
        assertEquals(1, requeued.speakerAttempts)
    }

    @Test
    fun interruptedClaimOnItsLastAttemptFailsWithAReason() {
        var current = memory()
        repeat(MAX_SPEAKER_ATTEMPTS) {
            current = current.claimedForSpeakerAnalysis().requeuedForSpeakerAnalysis()
        }

        assertEquals(SpeakerStatus.FAILED, current.speakerStatus)
        assertEquals(MAX_SPEAKER_ATTEMPTS, current.speakerAttempts)
        assertNotNull(current.lastSpeakerError)
    }

    @Test
    fun finishedAnalysisRecordsItsVersionAndClearsTheError() {
        val done = memory()
            .claimedForSpeakerAnalysis()
            .withFailedSpeakerAnalysis("oops")
            .claimedForSpeakerAnalysis()
            .withFinishedSpeakerAnalysis(SPEAKER_ANALYSIS_VERSION)

        assertEquals(SpeakerStatus.DONE, done.speakerStatus)
        assertEquals(SPEAKER_ANALYSIS_VERSION, done.speakerAnalysisVersion)
        assertNull(done.lastSpeakerError)
    }

    @Test
    fun skipKeepsAFinishedAnalysis() {
        val done = memory().withFinishedSpeakerAnalysis(SPEAKER_ANALYSIS_VERSION)

        assertEquals(SpeakerStatus.DONE, done.withSpeakerSkipped().speakerStatus)
        assertEquals(SpeakerStatus.SKIPPED, memory().withSpeakerSkipped().speakerStatus)
    }

    @Test
    fun embeddingEqualityComparesVectorContents() {
        val first = SpeakerEmbedding("e", "m", null, floatArrayOf(1f, 2f), 1, "o", 0L)
        val second = SpeakerEmbedding("e", "m", null, floatArrayOf(1f, 2f), 1, "o", 0L)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == SpeakerEmbedding("e", "m", null, floatArrayOf(1f, 3f), 1, "o", 0L))
    }

    @Test
    fun embeddingTextLeavesOutTheVector() {
        val text = SpeakerEmbedding("e", "m", null, floatArrayOf(0.123456f), 1, "o", 0L).toString()

        assertFalse(text.contains("0.12"))
    }

    @Test
    fun occurrenceHasASpeakerExactlyWhenItIsLinked() {
        assertFailsWith<IllegalArgumentException> {
            SpeakerOccurrence(
                id = "o",
                memoryId = "m",
                ordinal = 1,
                speakerId = "s",
                link = SpeakerLink.NONE,
                rejectedSpeakerIds = emptyList(),
                totalSpeechMs = 0L,
                sampleStartMs = 0L,
                sampleEndMs = 1L,
                embeddingId = "e",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeakerOccurrence(
                id = "o",
                memoryId = "m",
                ordinal = 1,
                speakerId = null,
                link = SpeakerLink.AUTO,
                rejectedSpeakerIds = emptyList(),
                totalSpeechMs = 0L,
                sampleStartMs = 0L,
                sampleEndMs = 1L,
                embeddingId = "e",
            )
        }
    }

    @Test
    fun speakerKeepsNoMoreThanTheEmbeddingLimit() {
        assertFailsWith<IllegalArgumentException> {
            Speaker("s", "Ana", 0L, 0L, MAX_EMBEDDINGS_PER_SPEAKER + 1)
        }
    }
}
