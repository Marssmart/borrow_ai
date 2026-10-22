package org.deer.borrowai.data.app

import arrow.core.Either
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.FakeMemoryService
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackRequest
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingResult
import org.deer.borrowai.data.service.model.ModelDownloadError
import org.deer.borrowai.data.service.recording.TranscriptionScheduler
import org.deer.borrowai.data.service.recording.TranscriptionWorkRunner
import org.deer.borrowai.data.service.summary.MemorySummarizer
import org.deer.borrowai.data.service.summary.SummaryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppServicesCloseTest {
    private val recordingsRoot: Path = "/app/files/recordings".toPath()
    private val expectedOrder = listOf("media close", "await", "background jobs", "callbacks", "summarizer", "database")

    @Test
    fun `media close failure still awaits saves, cancels jobs and closes the database`() = runTest {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val media = RecordingMediaSession(events, closeFailure = IllegalStateException("media close"))
        val services = servicesWith(media, events, failures, RecordingSummarizer(events))

        services.close()
        advanceUntilIdle()

        assertEquals("media close", failures.single().message)
        assertEquals(expectedOrder, events)
    }

    @Test
    fun `a later failure is added as suppressed rather than replacing the first`() = runTest {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val awaitFailure = IllegalStateException("await")
        val media = RecordingMediaSession(events, awaitFailure = awaitFailure)
        val summarizer = RecordingSummarizer(events, closeFailure = IllegalArgumentException("summarizer"))
        val services = servicesWith(media, events, failures, summarizer)

        services.close()
        advanceUntilIdle()

        val reported = failures.single()
        assertSame(awaitFailure, reported)
        assertEquals(listOf("summarizer"), reported.suppressed.map { it.message })
        assertEquals(expectedOrder, events)
    }

    @Test
    fun `a media close failure is reported first and later failures are suppressed onto it`() = runTest {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val mediaFailure = IllegalStateException("media close")
        val media = RecordingMediaSession(
            events,
            closeFailure = mediaFailure,
            awaitFailure = IllegalStateException("await"),
        )
        val services = servicesWith(media, events, failures, RecordingSummarizer(events))

        services.close()
        advanceUntilIdle()

        val reported = failures.single()
        assertSame(mediaFailure, reported)
        assertEquals(listOf("await"), reported.suppressed.map { it.message })
        assertEquals(expectedOrder, events)
    }

    /**
     * Builds the graph around [media] and [summarizer], routing close-scope failures into [failures] and
     * recording each close step in [events]. [advanceUntilIdle] then runs the drain to completion.
     */
    private fun TestScope.servicesWith(
        media: MediaSessionService,
        events: MutableList<String>,
        failures: MutableList<Throwable>,
        summarizer: MemorySummarizer,
    ): AppServices {
        val clock = EpochClock { 0L }
        val backgroundJob = Job().also { job -> job.invokeOnCompletion { events += "background jobs" } }
        val callbackGroup = Job().also { job -> job.invokeOnCompletion { events += "callbacks" } }
        val closeScope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, failure -> failures += failure },
        )
        return AppServices(
            modelSetup = UnusedModelSetup,
            memories = FakeMemoryService(epochClock = clock),
            mediaSession = media,
            transcriptionRunner = TranscriptionWorkRunner(
                memoryService = FakeMemoryService(epochClock = clock),
                recordingsRoot = recordingsRoot,
                batchTranscriber = null,
                scheduler = TranscriptionScheduler.None,
            ),
            graphJob = SupervisorJob(),
            closeScope = closeScope,
            backgroundJobs = listOf(backgroundJob),
            callbackGroup = callbackGroup,
            summarizer = summarizer,
            onClose = { events += "database" },
        )
    }

    private class RecordingMediaSession(
        private val events: MutableList<String>,
        private val closeFailure: Throwable? = null,
        private val awaitFailure: Throwable? = null,
    ) : MediaSessionService {
        override val state: StateFlow<MediaSessionState> = MutableStateFlow(MediaSessionState.Idle)

        override suspend fun awaitPendingWork() {
            events += "await"
            awaitFailure?.let { throw it }
        }

        override fun close() {
            events += "media close"
            closeFailure?.let { throw it }
        }

        override fun startRecording(holdToRecord: Boolean) = error("not used by close")

        override fun stopRecording(): RecordingResult? = error("not used by close")

        override fun requestPlayback(target: PlaybackTarget): Either<PlaybackError, PlaybackRequest> =
            error("not used by close")

        override fun confirmPendingPlayback(): Either<PlaybackError, PlaybackRequest> = error("not used by close")

        override fun cancelPendingPlayback(): Boolean = error("not used by close")

        override fun stopPlayback() = error("not used by close")

        override fun dismissRecordingError() = error("not used by close")
    }

    private class RecordingSummarizer(
        private val events: MutableList<String>,
        private val closeFailure: Throwable? = null,
    ) : MemorySummarizer {
        override suspend fun summarize(transcription: String): SummaryResult = error("not used by close")

        override suspend fun close() {
            events += "summarizer"
            closeFailure?.let { throw it }
        }
    }

    private object UnusedModelSetup : ModelSetupService {
        override val state: StateFlow<ModelSetupState> = MutableStateFlow(ModelSetupState())

        override suspend fun prepare(): Either<ModelDownloadError, Unit> = error("not used by close")

        override suspend fun downloadOverMobileData() = error("not used by close")
    }
}
