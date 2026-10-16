package org.deer.borrowai.data.app

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotbase.Database
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Path
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.demo.DemoModelSetupService
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService
import org.deer.borrowai.data.service.kotbase.KotbaseMemoryService
import org.deer.borrowai.data.service.model.UnavailableModelSetupService
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.DeletedMemoryPurger
import org.deer.borrowai.data.service.recording.MicrophonePermission
import org.deer.borrowai.data.service.recording.RecordingMediaSessionService
import org.deer.borrowai.data.service.recording.RecordingReconciler

class AppServices private constructor(
    val mode: AppMode,
    val modelSetup: ModelSetupService,
    val memories: MemoryService,
    val mediaSession: MediaSessionService,
    private val graphJob: CompletableJob,
    private val closeScope: CoroutineScope,
    private val onClose: () -> Unit = {},
) {
    private val closed = MutableStateFlow(false)

    /**
     * Closes the media session, then cancels the graph and runs [onClose] once any memory save started
     * before close has finished. Saves are joined first so they are not cut off by the cancellation.
     */
    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return

        try {
            mediaSession.close()
        } catch (failure: Throwable) {
            finishClose()
            throw failure
        }
        closeScope.launch {
            try {
                mediaSession.awaitPendingWork()
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
        /**
         * Picks the graph for [startupModeFlag]. REAL opens the Kotbase database named [databaseName]
         * in the platform's default directory.
         */
        suspend fun create(
            startupModeFlag: String?,
            epochClock: EpochClock,
            recordingsRoot: Path,
            recorder: AudioRecorder,
            microphonePermission: MicrophonePermission,
            databaseName: String = DATABASE_NAME,
        ): AppServices = when (AppMode.fromStartupFlag(startupModeFlag)) {
            AppMode.DEMO -> createFromStartupFlag(
                startupModeFlag = startupModeFlag,
                epochClock = epochClock,
                recordingsRoot = recordingsRoot,
                recorder = recorder,
                microphonePermission = microphonePermission,
            )
            AppMode.REAL -> createReal(
                database = Database(databaseName),
                recordingsRoot = recordingsRoot,
                recorder = recorder,
                microphonePermission = microphonePermission,
                epochClock = epochClock,
            )
        }

        private const val DATABASE_NAME = "borrow-ai"

        /**
         * Builds the production graph on an already-open [database]. Ownership of [database] passes
         * to the returned services, which close it in [close].
         */
        suspend fun createReal(
            database: Database,
            recordingsRoot: Path,
            recorder: AudioRecorder,
            microphonePermission: MicrophonePermission,
            epochClock: EpochClock,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            mediaSessionDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        ): AppServices {
            val graphJob = SupervisorJob()
            try {
                val graphScope = CoroutineScope(graphJob + dispatcher)
                val mediaScope = CoroutineScope(graphJob + mediaSessionDispatcher)
                val memories = KotbaseMemoryService.open(database = database, epochClock = epochClock)
                val mediaSession = RecordingMediaSessionService(
                    memoryService = memories,
                    recorder = recorder,
                    microphonePermission = microphonePermission,
                    recordingsRoot = recordingsRoot,
                    scope = mediaScope,
                    epochClock = epochClock,
                )
                RecordingReconciler(
                    memoryService = memories,
                    recordingsRoot = recordingsRoot,
                    isRecording = { mediaSession.isBusy },
                ).start(scope = graphScope)
                DeletedMemoryPurger(
                    memoryService = memories,
                    recordingsRoot = recordingsRoot,
                    isRecording = { mediaSession.isBusy },
                ).start(scope = graphScope)
                return AppServices(
                    mode = AppMode.REAL,
                    modelSetup = UnavailableModelSetupService(),
                    memories = memories,
                    mediaSession = mediaSession,
                    graphJob = graphJob,
                    closeScope = graphScope,
                    onClose = { database.close() },
                )
            } catch (failure: Throwable) {
                graphJob.cancel()
                database.close()
                throw failure
            }
        }

        /**
         * Demo graph: in-memory memories and a model stub, but the media session is the real
         * [RecordingMediaSessionService] writing to [recordingsRoot] through [recorder].
         */
        fun createFromStartupFlag(
            startupModeFlag: String?,
            epochClock: EpochClock,
            recordingsRoot: Path,
            recorder: AudioRecorder,
            microphonePermission: MicrophonePermission,
        ): AppServices = createFromStartupFlag(
            startupModeFlag = startupModeFlag,
            epochClock = epochClock,
            recordingsRoot = recordingsRoot,
            recorder = recorder,
            microphonePermission = microphonePermission,
            dispatcher = Dispatchers.Default,
        )

        internal fun createFromStartupFlag(
            startupModeFlag: String?,
            epochClock: EpochClock,
            recordingsRoot: Path,
            recorder: AudioRecorder,
            microphonePermission: MicrophonePermission,
            dispatcher: CoroutineDispatcher,
            mediaSessionDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        ): AppServices {
            val mode = AppMode.fromStartupFlag(startupModeFlag)
            return when (mode) {
                AppMode.DEMO -> {
                    val graphJob = SupervisorJob()
                    val graphScope = CoroutineScope(graphJob + dispatcher)
                    val mediaScope = CoroutineScope(graphJob + mediaSessionDispatcher)
                    val memories = InMemoryDemoMemoryService(epochClock = epochClock)
                    AppServices(
                        mode = mode,
                        modelSetup = DemoModelSetupService(),
                        memories = memories,
                        mediaSession = RecordingMediaSessionService(
                            memoryService = memories,
                            recorder = recorder,
                            microphonePermission = microphonePermission,
                            recordingsRoot = recordingsRoot,
                            scope = mediaScope,
                            epochClock = epochClock,
                        ),
                        graphJob = graphJob,
                        closeScope = graphScope,
                    )
                }

                AppMode.REAL -> throw RealServicesUnavailableException()
            }
        }
    }
}

class RealServicesUnavailableException : IllegalStateException(
    "Real mode was requested, but production data services have not been implemented.",
)
