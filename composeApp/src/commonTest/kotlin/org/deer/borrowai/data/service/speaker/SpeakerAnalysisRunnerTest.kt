package org.deer.borrowai.data.service.speaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.MAX_SPEAKER_ATTEMPTS
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.SPEAKER_ANALYSIS_VERSION
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerSkipReason
import org.deer.borrowai.data.domain.SpeakerStatus
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.FakeSpeakerService
import org.deer.borrowai.data.service.MediaSessionState

private fun turn(startMs: Long, endMs: Long, vararg values: Float) = SpeechTurn(startMs, endMs, floatArrayOf(*values))

class SpeakerAnalysisRunnerTest {
    private val root = "/root".toPath()

    private class ScriptedAnalyzer(
        var available: Boolean = true,
        var script: suspend (Path) -> List<SpeechTurn> = { listOf(turn(0, 2_000, 1f, 0f, 0f, 0f)) },
    ) : SpeakerAnalyzer {
        val analyzedPaths = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = available

        override suspend fun analyze(recordingPath: Path, shouldPause: () -> Boolean): List<SpeechTurn> {
            analyzedPaths += recordingPath.toString()
            if (shouldPause()) throw SpeakerAnalysisPausedException()
            val turns = script(recordingPath)
            if (shouldPause()) throw SpeakerAnalysisPausedException()
            return turns
        }
    }

    private fun memories(clock: () -> Long = { 1_800_000_000_000L }) = FakeMemoryService(epochClock = EpochClock { clock() })

    private suspend fun FakeMemoryService.addAudioMemory(id: String): Memory =
        create(MemoryDraft("t", "s", "x", "recordings/$id.m4a"), id = id).getOrNull()!!

    private suspend fun FakeMemoryService.get(id: String): Memory = findById(id).getOrNull()!!

    private fun runner(
        memories: FakeMemoryService,
        speakers: FakeSpeakerService,
        analyzer: SpeakerAnalyzer,
        scope: CoroutineScope,
        isRecording: () -> Boolean = { false },
    ) = SpeakerAnalysisRunner(memories, speakers, analyzer, root, isRecording, scope)

    @Test
    fun drainAnalyzesPendingMemoryAndStoresItsOccurrences() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        val analyzer = ScriptedAnalyzer(
            script = {
                listOf(
                    turn(0, 5_000, 1f, 0f, 0f, 0f),
                    turn(5_000, 10_000, 0f, 1f, 0f, 0f),
                    turn(10_000, 15_000, 1f, 0f, 0f, 0f),
                )
            },
        )

        val finished = runner(memories, speakers, analyzer, this).drain()

        assertTrue(finished)
        val memory = memories.get("a")
        assertEquals(SpeakerStatus.DONE, memory.speakerStatus)
        assertEquals(SPEAKER_ANALYSIS_VERSION, memory.speakerAnalysisVersion)
        val occurrences = speakers.findOccurrences("a").getOrNull()!!
        assertEquals(listOf(1, 2), occurrences.map { it.ordinal })
        assertEquals(listOf(0L, 5_000L), occurrences.map { it.sampleStartMs })
        assertEquals(listOf(5_000L, 10_000L), occurrences.map { it.sampleEndMs })
    }

    @Test
    fun speakerLibraryChangesRelinkUnknownMemoriesWithoutReanalyzingAudio() = runTest {
        val memories = memories()
        memories.addAudioMemory("known")
        memories.addAudioMemory("unknown")
        val speakers = FakeSpeakerService(enabled = true)
        val alice = speakers.createSpeaker("Alice").getOrNull()!!
        speakers.replaceOccurrences(
            memoryId = "known",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(
                OccurrenceDraft(
                    ordinal = 1,
                    speakerId = alice.id,
                    link = SpeakerLink.AUTO,
                    totalSpeechMs = 3_000L,
                    sampleStartMs = 0L,
                    sampleEndMs = 3_000L,
                    centroid = floatArrayOf(1f, 0f, 0f, 0f),
                ),
            ),
        )
        speakers.replaceOccurrences(
            memoryId = "unknown",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(
                OccurrenceDraft(
                    ordinal = 1,
                    speakerId = null,
                    link = SpeakerLink.NONE,
                    totalSpeechMs = 3_000L,
                    sampleStartMs = 0L,
                    sampleEndMs = 3_000L,
                    centroid = floatArrayOf(0.9f, 0.1f, 0f, 0f),
                ),
            ),
        )
        val analyzer = ScriptedAnalyzer(available = false)
        val runner = runner(memories, speakers, analyzer, this)

        val watchJob = runner.watchSpeakerChanges()
        advanceUntilIdle()

        val knownOccurrence = speakers.findOccurrences("known").getOrNull()!!.single()
        val unknownOccurrence = speakers.findOccurrences("unknown").getOrNull()!!.single()
        assertEquals(alice.id, knownOccurrence.speakerId)
        assertEquals(SpeakerLink.AUTO, unknownOccurrence.link)
        assertEquals(alice.id, unknownOccurrence.speakerId)
        assertTrue(analyzer.analyzedPaths.isEmpty())

        watchJob.cancel()
        runner.shutdown()
    }

    @Test
    fun repeatedFailuresEndInFailedAfterTheAttemptCap() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        val analyzer = ScriptedAnalyzer(script = { throw IllegalStateException("boom") })

        val finished = runner(memories, speakers, analyzer, this).drain()

        assertTrue(finished)
        val memory = memories.get("a")
        assertEquals(SpeakerStatus.FAILED, memory.speakerStatus)
        assertEquals(MAX_SPEAKER_ATTEMPTS, memory.speakerAttempts)
        assertEquals("IllegalStateException", memory.lastSpeakerError)
    }

    @Test
    fun recordingInProgressLeavesMemoriesPending() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        val analyzer = ScriptedAnalyzer()

        val finished = runner(memories, speakers, analyzer, this, isRecording = { true }).drain()

        assertFalse(finished)
        assertEquals(SpeakerStatus.PENDING, memories.get("a").speakerStatus)
        assertEquals(0, memories.get("a").speakerAttempts)
        assertTrue(analyzer.analyzedPaths.isEmpty())
    }

    @Test
    fun recordingStartedDuringAnalysisRequeuesTheClaimWithoutCountingAFailure() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        var recording = false
        val analyzer = ScriptedAnalyzer(
            script = {
                recording = true
                listOf(turn(0, 2_000, 1f, 0f, 0f, 0f))
            },
        )

        val finished = runner(memories, speakers, analyzer, this, isRecording = { recording }).drain()

        assertFalse(finished)
        assertEquals(SpeakerStatus.PENDING, memories.get("a").speakerStatus)
        assertEquals(0, memories.get("a").speakerAttempts)
        assertTrue(speakers.findOccurrences("a").getOrNull()!!.isEmpty())
    }

    @Test
    fun featureOffSkipsWaitingMemoriesWithoutClaiming() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = false)
        memories.addAudioMemory("a")

        assertFalse(runner(memories, speakers, ScriptedAnalyzer(), this).drain())
        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)
        assertEquals(0, memories.get("a").speakerAttempts)
    }

    @Test
    fun unavailableAnalyzerSkipsWaitingMemoriesWithoutClaiming() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")

        val finished = runner(memories, speakers, ScriptedAnalyzer(available = false), this).drain()

        assertFalse(finished)
        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)
        assertEquals(0, memories.get("a").speakerAttempts)
    }

    @Test
    fun noSpeechIsSkippedWithAReasonThatPreventsUnnecessaryReanalysis() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        val analyzer = ScriptedAnalyzer(script = { emptyList() })
        val runner = runner(memories, speakers, analyzer, this)

        assertTrue(runner.drain())
        val memory = memories.get("a")
        assertEquals(SpeakerStatus.SKIPPED, memory.speakerStatus)
        assertEquals(SpeakerSkipReason.NO_SPEECH, memory.speakerSkipReason)

        assertEquals(0, runner.requeueSkippedAndRequestDrain().getOrNull())
        advanceUntilIdle()
        assertEquals(1, analyzer.analyzedPaths.size)
    }

    @Test
    fun enablingAfterModelAvailabilityRequeuesPreviouslySkippedMemories() = runTest {
        val memories = memories()
        memories.addAudioMemory("a")
        memories.skipSpeakerAnalysis("a")
        val analyzer = ScriptedAnalyzer()
        val runner = runner(memories, FakeSpeakerService(enabled = true), analyzer, this)

        assertEquals(1, runner.requeueSkippedAndRequestDrain().getOrNull())
        advanceUntilIdle()

        assertEquals(SpeakerStatus.DONE, memories.get("a").speakerStatus)
        assertEquals(listOf("/root/recordings/a.m4a"), analyzer.analyzedPaths)
    }

    @Test
    fun recordingToIdleTransitionRetriesSkippedMemories() = runTest {
        val memories = memories()
        memories.addAudioMemory("a")
        memories.skipSpeakerAnalysis("a")
        val state = MutableStateFlow<MediaSessionState>(
            MediaSessionState.Recording(elapsedMillis = 0, transcription = ""),
        )
        var busy = true
        val analyzer = ScriptedAnalyzer()
        val runner = runner(
            memories = memories,
            speakers = FakeSpeakerService(enabled = true),
            analyzer = analyzer,
            scope = this,
            isRecording = { busy },
        )

        val watcher = runner.watchRecordingState(state)
        runCurrent()
        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)

        state.value = MediaSessionState.Idle
        runCurrent()
        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)

        busy = false
        advanceTimeBy(100)
        runCurrent()

        assertEquals(SpeakerStatus.DONE, memories.get("a").speakerStatus)
        assertEquals(listOf("/root/recordings/a.m4a"), analyzer.analyzedPaths)
        watcher.cancel()
    }

    @Test
    fun transcriptionWithFeatureOffSkipsTheMemory() = runTest {
        val memories = memories()
        memories.addAudioMemory("a")
        val runner = runner(memories, FakeSpeakerService(enabled = false), ScriptedAnalyzer(), this)

        runner.onTranscriptionSucceeded("a")

        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)
    }

    @Test
    fun transcriptionWithUnavailableAnalyzerSkipsTheMemory() = runTest {
        val memories = memories()
        memories.addAudioMemory("a")
        val runner = runner(
            memories,
            FakeSpeakerService(enabled = true),
            ScriptedAnalyzer(available = false),
            this,
        )

        runner.onTranscriptionSucceeded("a")

        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)
    }

    @Test
    fun transcriptionWithFeatureOnAnalyzesInTheBackground() = runTest {
        val memories = memories()
        memories.addAudioMemory("a")
        val runner = runner(memories, FakeSpeakerService(enabled = true), ScriptedAnalyzer(), this)

        runner.onTranscriptionSucceeded("a")
        advanceUntilIdle()

        assertEquals(SpeakerStatus.DONE, memories.get("a").speakerStatus)
    }

    @Test
    fun memoryWithoutAudioIsSkippedWithoutAnalysis() = runTest {
        val memories = memories()
        memories.create(MemoryDraft("t", "s", "x"), id = "a")
        val analyzer = ScriptedAnalyzer()

        runner(memories, FakeSpeakerService(enabled = true), analyzer, this).drain()

        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)
        assertTrue(analyzer.analyzedPaths.isEmpty())
    }

    @Test
    fun recordingWithNoSpeechIsSkipped() = runTest {
        val memories = memories()
        memories.addAudioMemory("a")
        val analyzer = ScriptedAnalyzer(script = { emptyList() })

        runner(memories, FakeSpeakerService(enabled = true), analyzer, this).drain()

        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)
        assertNull(memories.get("a").speakerAnalysisVersion)
    }

    @Test
    fun newestMemoriesAreAnalyzedFirst() = runTest {
        var now = 1L
        val memories = memories { now }
        memories.addAudioMemory("old")
        now = 2L
        memories.addAudioMemory("new")
        val analyzer = ScriptedAnalyzer()

        runner(memories, FakeSpeakerService(enabled = true), analyzer, this).drain()

        assertTrue(analyzer.analyzedPaths.first().endsWith("new.m4a"))
        assertTrue(analyzer.analyzedPaths.last().endsWith("old.m4a"))
    }

    @Test
    fun shutdownWaitsForTheRunningDrainAndBlocksLaterWork() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        memories.addAudioMemory("b")
        val gate = CompletableDeferred<Unit>()
        val analyzer = ScriptedAnalyzer(script = {
            gate.await()
            listOf(turn(0, 2_000, 1f, 0f, 0f, 0f))
        })
        val runner = runner(memories, speakers, analyzer, this)

        val drain = async { runner.drain() }
        advanceUntilIdle()
        assertEquals(SpeakerStatus.ANALYZING, memories.get("a").speakerStatus)

        val shutdown = async { runner.shutdown() }
        advanceUntilIdle()
        assertFalse(shutdown.isCompleted)

        gate.complete(Unit)
        shutdown.await()
        assertFalse(drain.await())
        assertFalse(runner.drain())
        assertEquals(SpeakerStatus.PENDING, memories.get("b").speakerStatus)
    }

    @Test
    fun drainRequeuesAnInterruptedAnalysisAndAnalyzesItAgain() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("stuck")
        memories.claimNextPendingSpeakerAnalysis()
        assertEquals(SpeakerStatus.ANALYZING, memories.get("stuck").speakerStatus)

        assertTrue(runner(memories, speakers, ScriptedAnalyzer(), this).drain())

        assertEquals(SpeakerStatus.DONE, memories.get("stuck").speakerStatus)
    }

    @Test
    fun interruptedAnalysisIsSkippedWhenTheFeatureIsOff() = runTest {
        val memories = memories()
        memories.addAudioMemory("stuck")
        memories.claimNextPendingSpeakerAnalysis()

        runner(memories, FakeSpeakerService(enabled = false), ScriptedAnalyzer(), this).drain()

        assertEquals(SpeakerStatus.SKIPPED, memories.get("stuck").speakerStatus)
    }

    @Test
    fun memoryPurgedWhileAnalyzedLeavesNoVoiceprints() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        val gate = CompletableDeferred<Unit>()
        val analyzer = ScriptedAnalyzer(script = {
            gate.await()
            listOf(turn(0, 2_000, 1f, 0f, 0f, 0f))
        })
        val runner = runner(memories, speakers, analyzer, this)
        val drain = async { runner.drain() }
        advanceUntilIdle()

        // The purger removes speaker rows before the record. Both happen before the analysis writes anything.
        memories.delete("a")
        speakers.deleteForMemory("a")
        memories.purge("a")
        gate.complete(Unit)

        assertTrue(drain.await())
        assertTrue(speakers.findOccurrences("a").getOrNull()!!.isEmpty())
        assertTrue(memories.findById("a").isLeft())
    }

    @Test
    fun turningTheFeatureOffDuringAnalysisDropsTheResult() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        val gate = CompletableDeferred<Unit>()
        val analyzer = ScriptedAnalyzer(script = {
            gate.await()
            listOf(turn(0, 2_000, 1f, 0f, 0f, 0f))
        })
        val runner = runner(memories, speakers, analyzer, this)
        val drain = async { runner.drain() }
        advanceUntilIdle()

        speakers.setFeatureEnabled(false)
        gate.complete(Unit)

        assertFalse(drain.await())
        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)
        assertTrue(speakers.findOccurrences("a").getOrNull()!!.isEmpty())
    }

    @Test
    fun rebuildWithNoSpeechClearsRowsLeftByAnEarlierAttempt() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        speakers.replaceOccurrences(
            "a",
            SPEAKER_ANALYSIS_VERSION,
            listOf(OccurrenceDraft(1, null, SpeakerLink.NONE, 2_000, 0, 2_000, floatArrayOf(1f, 0f, 0f, 0f))),
        )
        assertEquals(1, speakers.findOccurrences("a").getOrNull()!!.size)

        assertTrue(runner(memories, speakers, ScriptedAnalyzer(script = { emptyList() }), this).drain())

        assertEquals(SpeakerStatus.SKIPPED, memories.get("a").speakerStatus)
        assertTrue(speakers.findOccurrences("a").getOrNull()!!.isEmpty())
    }

    @Test
    fun storeFailuresEndInFailedAfterTheAttemptCap() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true, failWrites = true)
        memories.addAudioMemory("a")

        assertTrue(runner(memories, speakers, ScriptedAnalyzer(), this).drain())

        val memory = memories.get("a")
        assertEquals(SpeakerStatus.FAILED, memory.speakerStatus)
        assertEquals(MAX_SPEAKER_ATTEMPTS, memory.speakerAttempts)
        assertEquals("Speaker occurrences could not be saved", memory.lastSpeakerError)
    }

    @Test
    fun cancellationStopsTheDrainAndLeavesTheMemoryForTheNextDrain() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        var calls = 0
        val analyzer = ScriptedAnalyzer(script = {
            calls++
            if (calls == 1) throw CancellationException("stopped")
            listOf(turn(0, 2_000, 1f, 0f, 0f, 0f))
        })
        val runner = runner(memories, speakers, analyzer, this)

        assertFailsWith<CancellationException> { runner.drain() }
        assertEquals(SpeakerStatus.ANALYZING, memories.get("a").speakerStatus)

        assertTrue(runner.drain())
        assertEquals(SpeakerStatus.DONE, memories.get("a").speakerStatus)
    }

    @Test
    fun libraryReadFailureLeavesTheMemoryPendingWithoutUsingAnAttempt() = runTest {
        val memories = memories()
        val speakers = FakeSpeakerService(enabled = true)
        memories.addAudioMemory("a")
        speakers.failReads = true

        assertFalse(runner(memories, speakers, ScriptedAnalyzer(), this).drain())

        assertEquals(SpeakerStatus.PENDING, memories.get("a").speakerStatus)
        assertEquals(0, memories.get("a").speakerAttempts)
    }
}
