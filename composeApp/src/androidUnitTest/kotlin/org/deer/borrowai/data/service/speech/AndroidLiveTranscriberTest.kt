package org.deer.borrowai.data.service.speech

import org.deer.borrowai.data.service.recording.LiveTranscriptionFailure
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the seams that do not need the native sherpa-onnx runtime or a real AudioRecord. Capture and decode
 * behavior on device is not covered here.
 */
class AndroidLiveTranscriberTest {
    private val waitMs = 5_000L

    @Test
    fun missingModelsReportsModelUnavailableOnce() {
        val failures = CopyOnWriteArrayList<LiveTranscriptionFailure>()
        val reported = CountDownLatch(1)
        val transcriber = AndroidLiveTranscriber(resolveModelPaths = { null })

        transcriber.start(onPartial = {}, onFailure = {
            failures.add(it)
            reported.countDown()
        })

        assertTrue(reported.await(waitMs, TimeUnit.MILLISECONDS))
        Thread.sleep(100)
        assertEquals(listOf(LiveTranscriptionFailure.MODEL_UNAVAILABLE), failures)
        transcriber.stop()
    }

    @Test
    fun stopDuringModelResolutionReportsNothing() {
        val resolveEntered = CountDownLatch(1)
        val releaseResolve = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<LiveTranscriptionFailure>()
        val transcriber = AndroidLiveTranscriber(resolveModelPaths = {
            resolveEntered.countDown()
            releaseResolve.await(waitMs, TimeUnit.MILLISECONDS)
            null
        })

        transcriber.start(onPartial = {}, onFailure = { failures.add(it) })
        try {
            assertTrue(resolveEntered.await(waitMs, TimeUnit.MILLISECONDS))
            transcriber.stop()
        } finally {
            releaseResolve.countDown()
        }

        Thread.sleep(100)
        assertTrue(failures.isEmpty(), "A user stop must not be reported as a failure, got $failures")
    }

    @Test
    fun stuckPreviousEngineReportsEngineFailedAndNeverResolvesAnotherModel() {
        val resolveCalls = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<LiveTranscriptionFailure>()
        val transcriber = AndroidLiveTranscriber(
            resolveModelPaths = {
                if (resolveCalls.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    releaseFirst.await(waitMs, TimeUnit.MILLISECONDS)
                }
                null
            },
            previousTeardownTimeoutMs = 50L,
        )

        try {
            // The first session blocks in model resolution and never finishes while the test holds releaseFirst.
            transcriber.start(onPartial = {}, onFailure = { failures.add(it) })
            assertTrue(firstEntered.await(waitMs, TimeUnit.MILLISECONDS))

            // A restart waits on the stuck teardown, times out, and reports. Each start stops the previous session,
            // so wait for each report before the next start: a user stop must not be reported.
            transcriber.start(onPartial = {}, onFailure = { failures.add(it) })
            awaitCount(failures, 1)
            transcriber.start(onPartial = {}, onFailure = { failures.add(it) })
            awaitCount(failures, 2)

            assertEquals(
                listOf(LiveTranscriptionFailure.ENGINE_FAILED, LiveTranscriptionFailure.ENGINE_FAILED),
                failures.toList(),
            )
            assertEquals(1, resolveCalls.get(), "No new engine may be opened while the previous one is stuck")
            val handoffThreads = Thread.getAllStackTraces().keys.count { it.name == "live-transcriber-handoff" }
            assertTrue(handoffThreads <= 1, "Timed-out handoffs must share one thread, found $handoffThreads")
        } finally {
            transcriber.stop()
            releaseFirst.countDown()
        }
    }

    @Test
    fun releasedTeardownUnblocksRestartsQueuedBehindIt() {
        val resolveCalls = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<LiveTranscriptionFailure>()
        val transcriber = AndroidLiveTranscriber(
            resolveModelPaths = {
                if (resolveCalls.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    releaseFirst.await(waitMs, TimeUnit.MILLISECONDS)
                }
                null
            },
            previousTeardownTimeoutMs = 500L,
        )

        try {
            transcriber.start(onPartial = {}, onFailure = { failures.add(it) })
            assertTrue(firstEntered.await(waitMs, TimeUnit.MILLISECONDS))

            // Several restarts give up behind the stuck teardown and share one queued handoff.
            repeat(3) {
                transcriber.start(onPartial = {}, onFailure = { failures.add(it) })
                awaitCount(failures, it + 1)
            }

            releaseFirst.countDown()
            // Give the shared handoff a moment to release the queued sessions before the next restart waits on them.
            Thread.sleep(100)

            transcriber.start(onPartial = {}, onFailure = { failures.add(it) })
            awaitCount(failures, 4)
            assertEquals(
                LiveTranscriptionFailure.MODEL_UNAVAILABLE,
                failures.last(),
                "A restart after the stuck teardown finished must open a new engine attempt",
            )
            assertEquals(2, resolveCalls.get(), "Exactly one new model resolution once the teardown completed")
        } finally {
            transcriber.stop()
            releaseFirst.countDown()
        }
    }

    private fun awaitCount(list: List<*>, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (list.size < expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, list.size, "Timed out waiting for $expected failure report(s)")
    }
}
