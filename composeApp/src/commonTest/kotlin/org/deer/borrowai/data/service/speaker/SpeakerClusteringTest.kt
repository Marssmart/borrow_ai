package org.deer.borrowai.data.service.speaker

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakerClusteringTest {
    private fun turn(startMs: Long, endMs: Long, vararg values: Float) =
        SpeechTurn(startMs, endMs, floatArrayOf(*values))

    /** A 10-dimensional vector with the given components set and the rest zero. */
    private fun axis(vararg weights: Pair<Int, Float>) = FloatArray(10).also { vector ->
        for ((index, weight) in weights) vector[index] = weight
    }

    @Test
    fun twoSpeakersStayInTwoClusters() {
        val labels = clusterTurns(
            listOf(
                turn(0, 2_000, 1f, 0f, 0f, 0f),
                turn(2_000, 4_000, 0f, 1f, 0f, 0f),
                turn(4_000, 6_000, 1f, 0.05f, 0f, 0f),
                turn(6_000, 8_000, 0.05f, 1f, 0f, 0f),
            ),
        )

        assertEquals(listOf(0, 1, 0, 1), labels.toList())
    }

    @Test
    fun oneSpeakerStaysOneCluster() {
        val labels = clusterTurns(
            listOf(
                turn(0, 2_000, 1f, 0f, 0f, 0f),
                turn(2_000, 4_000, 0.98f, 0.1f, 0f, 0f),
                turn(4_000, 6_000, 1f, 0.05f, 0f, 0f),
                turn(6_000, 8_000, 0.97f, 0.12f, 0f, 0f),
            ),
        )

        assertEquals(listOf(0, 0, 0, 0), labels.toList())
    }

    @Test
    fun shortSpeakerIsDroppedAsNoise() {
        val labels = clusterTurns(
            listOf(
                turn(0, 3_000, 1f, 0f, 0f, 0f),
                turn(3_000, 3_800, 0f, 0f, 1f, 0f),
                turn(3_800, 6_800, 1f, 0f, 0f, 0f),
            ),
        )

        assertEquals(listOf(0, -1, 0), labels.toList())
    }

    @Test
    fun speakersBeyondTheLimitMergeIntoTheNearestKeptSpeaker() {
        // Eight speakers on separate axes, then two extra voices. Each extra is 0.6 or 0.7 from its nearest kept
        // speaker, so it stays separate in the first pass and the cap merges it into that speaker.
        val kept = (0 until MAX_SPEAKERS).map { speaker ->
            SpeechTurn(speaker * 2_000L, speaker * 2_000L + 2_000L, axis(speaker to 1f))
        }
        val extras = listOf(
            SpeechTurn(16_000L, 18_000L, axis(3 to 0.4f, 8 to 0.9165f)),
            SpeechTurn(18_000L, 20_000L, axis(5 to 0.3f, 9 to 0.9539f)),
        )

        val labels = clusterTurns(kept + extras)

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 3, 5), labels.toList())
    }

    @Test
    fun noTurnsGiveNoLabels() {
        assertEquals(0, clusterTurns(emptyList()).size)
    }
}
