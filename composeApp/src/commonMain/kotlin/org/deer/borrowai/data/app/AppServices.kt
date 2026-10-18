package org.deer.borrowai.data.app

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotbase.Database
import okio.Path
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.kotbase.KotbaseMemoryService
import org.deer.borrowai.data.service.model.DownloadingModelSetupService
import org.deer.borrowai.data.service.model.KtorModelDownloader
import org.deer.borrowai.data.service.model.ModelDevice
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.createPlatformModelHttpClient
import org.deer.borrowai.data.service.model.modelDirectoryUnder
import org.deer.borrowai.data.service.recording.AudioPlayer
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.DeletedMemoryPurger
import org.deer.borrowai.data.service.recording.MicrophonePermission
import org.deer.borrowai.data.service.recording.RecordingMediaSessionService
import org.deer.borrowai.data.service.recording.RecordingReconciler

class AppServices private constructor(
    val modelSetup: ModelSetupService,
    val memories: MemoryService,
    val mediaSession: MediaSessionService,
    private val graphJob: CompletableJob,
    private val closeScope: CoroutineScope,
    private val backgroundJobs: List<Job> = emptyList(),
    private val onClose: () -> Unit = {},
) {
    // complete() returns true only for the first caller, so this latch makes close() run once.
    private val closeRequested = Job()

    /**
     * Closes the media session, then cancels the graph and runs [onClose] once any memory save started
     * before close has finished. Saves are joined first so they are not cut off by the cancellation.
     */
    fun close() {
        if (!closeRequested.complete()) return

        try {
            mediaSession.close()
        } catch (failure: Throwable) {
            finishClose()
            throw failure
        }
        closeScope.launch {
            try {
                mediaSession.awaitPendingWork()
                // Periodic sweeps must finish before the database closes under them.
                backgroundJobs.forEach { it.cancel() }
                backgroundJobs.forEach { it.join() }
            } finally {
                finishClose()
            }
        }
    }

    private fun finishClose() {
        graphJob.cancel()
        onClose()
    }

    companion object {
        private const val DATABASE_NAME = "borrow-ai"

        /**
         * Builds the production graph, opening the Kotbase database named [databaseName] in the
         * platform's default directory. Models live under [recordingsRoot] next to the recordings.
         */
        suspend fun create(
            epochClock: EpochClock,
            recordingsRoot: Path,
            recorder: AudioRecorder,
            audioPlayer: AudioPlayer,
            microphonePermission: MicrophonePermission,
            modelDevice: ModelDevice,
            databaseName: String = DATABASE_NAME,
        ): AppServices = createReal(
            database = Database(databaseName),
            recordingsRoot = recordingsRoot,
            recorder = recorder,
            audioPlayer = audioPlayer,
            microphonePermission = microphonePermission,
            modelDevice = modelDevice,
            epochClock = epochClock,
        )

        /**
         * Builds the production graph on an already-open [database]. Ownership of [database] passes
         * to the returned services, which close it in [close].
         */
        suspend fun createReal(
            database: Database,
            recordingsRoot: Path,
            recorder: AudioRecorder,
            audioPlayer: AudioPlayer,
            microphonePermission: MicrophonePermission,
            modelDevice: ModelDevice,
            epochClock: EpochClock,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            mediaSessionDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        ): AppServices {
            val graphJob = SupervisorJob()
            try {
                val graphScope = CoroutineScope(graphJob + dispatcher)
                val mediaScope = CoroutineScope(graphJob + mediaSessionDispatcher)
                val memories = KotbaseMemoryService.open(database = database, epochClock = epochClock).fold(
                    ifLeft = { error -> throw IllegalStateException("Memory store failed to open: $error") },
                    ifRight = { it },
                )
                val httpClient = createPlatformModelHttpClient()
                // Closed when the graph completes, including the failure path where cancel() runs below.
                graphJob.invokeOnCompletion { httpClient.close() }
                val modelSetup = DownloadingModelSetupService(
                    totalRamBytes = modelDevice.totalRamBytes,
                    store = ModelFileStore(modelDirectoryUnder(recordingsRoot.toString())),
                    downloader = KtorModelDownloader(httpClient),
                    network = modelDevice.network,
                    scope = graphScope,
                )
                val mediaSession = RecordingMediaSessionService(
                    memoryService = memories,
                    recorder = recorder,
                    audioPlayer = audioPlayer,
                    microphonePermission = microphonePermission,
                    recordingsRoot = recordingsRoot,
                    scope = mediaScope,
                    epochClock = epochClock,
                )
                val reconcilerJob = RecordingReconciler(
                    memoryService = memories,
                    recordingsRoot = recordingsRoot,
                    isRecording = { mediaSession.isBusy },
                    epochClock = epochClock,
                ).start(scope = graphScope)
                val purgerJob = DeletedMemoryPurger(
                    memoryService = memories,
                    recordingsRoot = recordingsRoot,
                    isRecording = { mediaSession.isBusy },
                ).start(scope = graphScope)
                return AppServices(
                    modelSetup = modelSetup,
                    memories = memories,
                    mediaSession = mediaSession,
                    graphJob = graphJob,
                    closeScope = graphScope,
                    backgroundJobs = listOf(reconcilerJob, purgerJob),
                    onClose = { database.close() },
                )
            } catch (failure: Throwable) {
                graphJob.cancel()
                database.close()
                throw failure
            }
        }
    }
}
