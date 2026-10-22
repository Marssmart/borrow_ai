package org.deer.borrowai.data.service.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the stall policy with a fake monotonic clock. The MediaCodec decode loop that calls it needs a device, so it is
 * not covered here.
 */
class DecodeStallGuardTest {
    private val millisToNanos = 1_000_000L
    private var nowNanos = 0L
    private val guard = DecodeStallGuard(timeoutMillis = 30_000L, nanoTime = { nowNanos })

    @Test
    fun idleWithinTimeoutDoesNotFail() {
        nowNanos = 30_000L * millisToNanos
        guard.check()
    }

    @Test
    fun idleBeyondTimeoutFailsWithStallException() {
        nowNanos = 30_001L * millisToNanos
        val failure = assertFailsWith<TranscriptionStalledException> { guard.check() }
        assertEquals("Audio decode made no progress for 30000 ms", failure.message)
    }

    @Test
    fun progressResetsTheIdleWindow() {
        nowNanos = 20_000L * millisToNanos
        guard.progress()
        nowNanos = 45_000L * millisToNanos
        guard.check()
        nowNanos = 50_001L * millisToNanos
        assertFailsWith<TranscriptionStalledException> { guard.check() }
    }
}
