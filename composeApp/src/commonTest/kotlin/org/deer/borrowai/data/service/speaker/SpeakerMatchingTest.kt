package org.deer.borrowai.data.service.speaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.SpeakerEmbedding
import org.deer.borrowai.data.domain.SpeakerLink

class SpeakerMatchingTest {
    private fun embedding(speakerId: String, vararg values: Float, modelVersion: Int = 1) = SpeakerEmbedding(
        id = "embedding-$speakerId-${values.joinToString()}",
        memoryId = "memory",
        speakerId = speakerId,
        vector = floatArrayOf(*values),
        modelVersion = modelVersion,
        sourceOccurrenceId = "occurrence",
        createdAtEpochMillis = 0L,
    )

    private fun scoresFor(
        centroid: FloatArray,
        library: List<SpeakerEmbedding>,
        rejected: Set<String> = emptySet(),
    ): Map<String, Double> = bestScores(normalized(centroid), speakerVectors(library, modelVersion = 1), rejected)

    @Test
    fun scoreAboveThresholdLinksAutomatically() {
        val library = listOf(embedding("alice", 1f, 0f, 0f, 0f))
        val scores = listOf(scoresFor(floatArrayOf(0.9f, 0.2f, 0f, 0f), library))

        val match = bestMatch(scores.single())
        val link = resolveLink(OccurrenceLink(speakerId = null, link = SpeakerLink.NONE), match)

        assertEquals(OccurrenceLink("alice", SpeakerLink.AUTO), link)
    }

    @Test
    fun scoreBelowThresholdStaysUnknown() {
        val library = listOf(embedding("alice", 1f, 0f, 0f, 0f))
        val scores = listOf(scoresFor(floatArrayOf(0.6f, 0.8f, 0f, 0f), library))

        val match = bestMatch(scores.single())

        assertNull(match)
        assertEquals(
            OccurrenceLink(null, SpeakerLink.NONE),
            resolveLink(OccurrenceLink(null, SpeakerLink.NONE), match),
        )
    }

    @Test
    fun rejectedSpeakerIsSkipped() {
        val library = listOf(embedding("alice", 1f, 0f, 0f, 0f))
        val scores = listOf(scoresFor(floatArrayOf(1f, 0f, 0f, 0f), library, rejected = setOf("alice")))

        assertTrue(scores.single().isEmpty())
        assertNull(bestMatch(scores.single()))
    }

    @Test
    fun bestScoreAcrossAllOfASpeakersEmbeddingsIsUsed() {
        val library = listOf(
            embedding("alice", 0f, 1f, 0f, 0f),
            embedding("alice", 1f, 0f, 0f, 0f),
        )

        val score = scoresFor(floatArrayOf(1f, 0f, 0f, 0f), library).getValue("alice")

        assertTrue(score > 0.99)
    }

    @Test
    fun embeddingsFromAnotherModelVersionAreNotCompared() {
        val library = listOf(embedding("alice", 1f, 0f, 0f, 0f, modelVersion = 2))

        assertTrue(scoresFor(floatArrayOf(1f, 0f, 0f, 0f), library).isEmpty())
    }

    @Test
    fun manualLinkIsNeverOverwritten() {
        val manual = OccurrenceLink("bob", SpeakerLink.MANUAL)

        val link = resolveLink(manual, SpeakerScore("alice", 0.99))

        assertEquals(manual, link)
    }
}
