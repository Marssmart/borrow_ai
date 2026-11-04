package org.deer.borrowai

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSpeedTest {
    @Test
    fun menuOffersFiveSpeedsFromSlowestToFastest() {
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 2f), PLAYBACK_SPEEDS)
    }

    @Test
    fun labelsShowTheSpeedWithoutTrailingZeros() {
        assertEquals("0.75×", formatPlaybackSpeed(0.75f))
        assertEquals("1×", formatPlaybackSpeed(1f))
        assertEquals("1.25×", formatPlaybackSpeed(1.25f))
        assertEquals("1.5×", formatPlaybackSpeed(1.5f))
        assertEquals("2×", formatPlaybackSpeed(2f))
    }
}
