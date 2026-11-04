package org.deer.borrowai.data.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.deer.borrowai.data.domain.Memory

class PlaybackTargetTest {
    private val memory = Memory(
        id = "m1",
        createdAtEpochMillis = 0L,
        title = "",
        summary = "Summary",
        transcription = "Transcript",
    )

    @Test
    fun speakerSampleTargetNeedsItsRange() {
        assertFailsWith<IllegalArgumentException> { PlaybackTarget("m1", PlaybackContent.SPEAKER_SAMPLE) }
    }

    @Test
    fun onlySpeakerSampleTargetsCarryARange() {
        assertFailsWith<IllegalArgumentException> {
            PlaybackTarget("m1", PlaybackContent.RECORDING, PlaybackSample(startMs = 0L, endMs = 1_000L))
        }
    }

    @Test
    fun sampleRangeStartsAtOrAfterZeroAndEndsAfterItStarts() {
        assertFailsWith<IllegalArgumentException> { PlaybackSample(startMs = 5_000L, endMs = 5_000L) }
        assertFailsWith<IllegalArgumentException> { PlaybackSample(startMs = -1L, endMs = 5_000L) }
    }

    @Test
    fun speakerSampleTargetsHaveNoText() {
        val target = PlaybackTarget("m1", PlaybackContent.SPEAKER_SAMPLE, PlaybackSample(startMs = 0L, endMs = 1_000L))

        assertEquals("", target.getText(memory))
    }
}
