package org.deer.borrowai.data.service.speaker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakerSamplingTest {
    private fun range(startMs: Long, endMs: Long) = TimeRange(startMs, endMs)

    @Test
    fun sampleNeverSpansASpeakerChange() {
        val sample = selectSample(
            own = listOf(range(0, 10_000)),
            others = listOf(range(4_000, 4_500)),
        )

        assertEquals(range(4_500, 10_000), sample)
    }

    @Test
    fun longestSingleSpeakerRunOfAtLeastFourSecondsWins() {
        val sample = selectSample(
            own = listOf(range(0, 3_000), range(5_000, 13_000)),
            others = emptyList(),
        )

        assertEquals(range(5_000, 13_000), sample)
    }

    @Test
    fun sampleIsClippedToEightSeconds() {
        assertEquals(range(0, 8_000), selectSample(listOf(range(0, 20_000)), emptyList()))
    }

    @Test
    fun keepsUpToThreeSeparatePreferredRunsInRecordingOrder() {
        val samples = selectSamples(
            own = listOf(range(0, 5_000), range(10_000, 20_000), range(25_000, 31_000), range(40_000, 42_000)),
            others = emptyList(),
        )

        assertEquals(listOf(range(0, 5_000), range(10_000, 18_000), range(25_000, 31_000)), samples)
    }

    @Test
    fun returnsSeveralShortRunsWhenNoRunMeetsThePreferredLength() {
        val samples = selectSamples(
            own = listOf(range(0, 1_000), range(3_000, 5_000)),
            others = emptyList(),
        )

        assertEquals(listOf(range(0, 1_000), range(3_000, 5_000)), samples)
    }

    @Test
    fun longestRunWinsWhenNoRunIsFourSeconds() {
        val sample = selectSample(
            own = listOf(range(0, 2_000), range(2_100, 3_500)),
            others = emptyList(),
        )

        assertEquals(range(0, 3_500), sample)
    }

    @Test
    fun shortPauseInsideOneSpeakersSpeechIsBridged() {
        val sample = selectSample(
            own = listOf(range(0, 2_000), range(2_500, 6_500)),
            others = emptyList(),
        )

        assertEquals(range(0, 6_500), sample)
    }

    @Test
    fun noSampleWhenOtherSpeakersCoverAllSpeech() {
        assertNull(selectSample(listOf(range(0, 1_000)), listOf(range(0, 1_000))))
    }

    @Test
    fun unionLengthCountsOverlapOnce() {
        assertEquals(2_000L, unionLength(listOf(range(0, 1_000), range(500, 2_000))))
        assertTrue(mergeRanges(listOf(range(0, 1_000), range(1_200, 2_000)), gapMs = 0L).size == 2)
    }
}
