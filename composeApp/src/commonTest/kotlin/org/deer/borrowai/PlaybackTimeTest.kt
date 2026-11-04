package org.deer.borrowai

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTimeTest {
    @Test
    fun formatsMinutesAndSecondsBelowAnHour() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("0:00", formatPlaybackTime(999L))
        assertEquals("0:59", formatPlaybackTime(59_999L))
        assertEquals("1:00", formatPlaybackTime(60_000L))
        assertEquals("59:59", formatPlaybackTime(3_599_999L))
    }

    @Test
    fun formatsHoursAndMinutesFromOneHour() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
        assertEquals("1:01:05", formatPlaybackTime(3_665_000L))
        assertEquals("10:00:00", formatPlaybackTime(36_000_000L))
    }

    @Test
    fun negativePositionsShowAsZero() {
        assertEquals("0:00", formatPlaybackTime(-1L))
    }
}
