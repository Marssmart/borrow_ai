package org.deer.borrowai.data.app

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotbase.Database
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okio.Path
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState
import org.deer.borrowai.data.service.SpeakerService
import org.deer.borrowai.data.service.category.CategoryModel
import org.deer.borrowai.data.service.category.DefaultMemoryCategorizer
import org.deer.borrowai.data.service.category.MemoryCategorizationRecoveryJob
import org.deer.borrowai.data.service.category.MemoryCategorizer
import org.deer.borrowai.data.service.category.UnavailableMemoryCategorizer
import org.deer.borrowai.data.service.background.BackgroundActivity
import org.deer.borrowai.data.service.background.BackgroundActivityTracker
import org.deer.borrowai.data.service.background.activeBackgroundActivities
import org.deer.borrowai.data.service.kotbase.KotbaseAppSettingsService
import org.deer.borrowai.data.service.kotbase.KotbaseMemoryService
import org.deer.borrowai.data.service.kotbase.KotbaseSpeakerService
import org.deer.borrowai.data.service.settings.AppSettingsService
import org.deer.borrowai.data.service.model.DownloadingModelSetupService
import org.deer.borrowai.data.service.model.KtorModelDownloader
import org.deer.borrowai.data.service.model.ModelDevice
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.createPlatformModelHttpClient
import org.deer.borrowai.data.service.model.modelDirectoryUnder
import org.deer.borrowai.data.service.recording.AudioDurationReader
import org.deer.borrowai.data.service.recording.AudioPlayer
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.BatchTranscriber
import org.deer.borrowai.data.service.recording.DeletedMemoryPurger
import org.deer.borrowai.data.service.recording.LiveTranscriber
import org.deer.borrowai.data.service.recording.MicrophonePermission
import org.deer.borrowai.data.service.recording.RecordingMediaSessionService
import org.deer.borrowai.data.service.recording.RecordingReconciler
import org.deer.borrowai.data.service.recording.TranscriptionScheduler
import org.deer.borrowai.data.service.recording.TranscriptionWorkRunner
import org.deer.borrowai.data.service.summary.MemorySummaryRecoveryJob
import org.deer.borrowai.data.service.summary.MemorySummarizer
import org.deer.borrowai.data.service.summary.UnavailableMemorySummarizer
import org.deer.borrowai.data.service.speaker.SpeakerAnalysisRunner
import org.deer.borrowai.data.service.speaker.SpeakerAnalyzer
import org.deer.borrowai.data.service.speaker.UnavailableSpeakerAnalyzer

/**
 * Shows the missing-model notice only until model setup reports ready, so it clears as soon as the download
 * finishes instead of waiting for the next recovery run.
 */
internal fun summaryModelMissingNotice(
    missing: StateFlow<Boolean>,
    setup: StateFlow<ModelSetupState>,
): Flow<Boolean> = combine(missing, setup) { isMissing, state -> isMissing && !state.isReady }

class AppServices internal constructor(
    val modelSetup: ModelSetupService,
    val memories: MemoryService,
    val speakers: SpeakerService,
    /** Finds speakers in recordings. Settings reads its availability, which stays false until a model is pinned. */
    val speakerAnalyzer: SpeakerAnalyzer = UnavailableSpeakerAnalyzer,
    val mediaSession: MediaSessionService,
    /** Runs the transcription jobs. Android's WorkManager workers reach it through a holder. */
    val transcriptionRunner: TranscriptionWorkRunner,
    private val graphJob: CompletableJob,
    private val closeScope: CoroutineScope,
    private val backgroundJobs: List<Job> = emptyList(),
    /** Parent of summary recoveries launched from transcription callbacks. Completed children detach themselves. */
    private val callbackGroup: Job = Job(),
    private val summarizer: MemorySummarizer = UnavailableMemorySummarizer,
    /** Releases the model runtime that the summarizer and categorizer share. Runs after both have stopped. */
    private val closeModelRuntime: suspend () -> Unit = {},
    /** Stops the speaker drains. Runs after the transcription runner stops and before the background jobs. */
    private val shutdownSpeakerAnalysis: suspend () -> Unit = {},
    /** True while the summary model file is missing, so recovery cannot summarize anything. */
    val summaryModelMissing: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow(),
    /** User preferences, such as whether the background activity indicator is shown. */
    val appSettings: AppSettingsService,
    /** The background work running now, including model downloads. Empty when the app is idle. */
    val backgroundActivities: StateFlow<Set<BackgroundActivity>> = MutableStateFlow(emptySet<BackgroundActivity>()).asStateFlow(),
    /** Coarse progress for the active transcription, or null when unavailable or inactive. */
    val transcriptionProgress: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow(),
    private val onClose: () -> Unit = {},
) {
    private val closed = MutableStateFlow(false)
    private val pendingWrites = PendingWrites(closeScope)

    /** Whether speaker analysis can run now: the user has opted in and a speaker model is available (section 9.1). */
    suspend fun speakerAnalysisActive(): Boolean =
        speakers.isFeatureEnabled().getOrNull() == true && speakerAnalyzer.isAvailable()

    /**
     * Starts a write that must finish even if the screen that asked for it leaves, such as a memory delete. [close]
     * waits for it before the database closes.
     */
    fun launchWrite(block: suspend () -> Unit) {
        pendingWrites.launch(block)
    }

    /**
     * Closes the media session, then cancels the graph and runs [onClose] once any memory save started
     * before close has finished. Saves are joined first so they are not cut off by the cancellation.
     */
    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        // Writes started after this point are refused, so the drain below sees every write that will ever run.
        pendingWrites.stopAccepting()

        val mediaFailure = runCatching { mediaSession.close() }.exceptionOrNull()
        closeScope.launch {
            // The media failure leads the list so it is the one reported, and later failures are suppressed onto it.
            val failures = mutableListOf<Throwable>()
            mediaFailure?.let { failures += it }
            // Each step records its failure and continues, so the database still closes after any earlier step throws.
            // Cancellation is recorded like any other failure on purpose.
            suspend fun step(block: suspend () -> Unit) {
                runCatching { block() }.onFailure { failures += it }
            }
            try {
                // Saves started before close must finish, even when the media close failed, before the database closes.
                step { mediaSession.awaitPendingWork() }
                // Memory writes started before close, such as a delete that committed when the page left, must finish too.
                step { pendingWrites.awaitAll() }
                // Transcription workers may still hold the runner, so stop it before the database closes under them.
                step { transcriptionRunner.shutdown() }
                // Speaker drains can run alongside transcription, so they stop before the database closes too.
                step { shutdownSpeakerAnalysis() }
                // Periodic sweeps and summary recoveries must finish before the database closes under them.
                step {
                    backgroundJobs.forEach { it.cancel() }
                    backgroundJobs.forEach { it.join() }
                }
                step {
                    callbackGroup.cancel()
                    callbackGroup.join()
                }
                step { summarizer.close() }
                step { closeModelRuntime() }
            } finally {
                finishClose()
            }
            failures.firstOrNull()?.let { first ->
                failures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun finishClose() {
        graphJob.cancel()
        onClose()
    }

    companion object {
        internal const val DATABASE_NAME = "borrow-ai"

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
            liveTranscriber: LiveTranscriber? = null,
            batchTranscriber: BatchTranscriber? = null,
            transcriptionScheduler: TranscriptionScheduler = TranscriptionScheduler.None,
            summarizer: MemorySummarizer = UnavailableMemorySummarizer,
            categoryModel: CategoryModel? = null,
            closeModelRuntime: suspend () -> Unit = {},
            speakerAnalyzer: SpeakerAnalyzer = UnavailableSpeakerAnalyzer,
            onSettingsReady: (AppSettingsService) -> Unit = {},
            audioDurationReader: AudioDurationReader = AudioDurationReader.None,
        ): AppServices = createReal(
            database = Database(databaseName),
            recordingsRoot = recordingsRoot,
            recorder = recorder,
            audioPlayer = audioPlayer,
            microphonePermission = microphonePermission,
            modelDevice = modelDevice,
            epochClock = epochClock,
            liveTranscriber = liveTranscriber,
            batchTranscriber = batchTranscriber,
            transcriptionScheduler = transcriptionScheduler,
            summarizer = summarizer,
            categoryModel = categoryModel,
            closeModelRuntime = closeModelRuntime,
            speakerAnalyzer = speakerAnalyzer,
            onSettingsReady = onSettingsReady,
            audioDurationReader = audioDurationReader,
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
            liveTranscriber: LiveTranscriber? = null,
            batchTranscriber: BatchTranscriber? = null,
            transcriptionScheduler: TranscriptionScheduler = TranscriptionScheduler.None,
            summarizer: MemorySummarizer = UnavailableMemorySummarizer,
            categoryModel: CategoryModel? = null,
            closeModelRuntime: suspend () -> Unit = {},
            speakerAnalyzer: SpeakerAnalyzer = UnavailableSpeakerAnalyzer,
            onSettingsReady: (AppSettingsService) -> Unit = {},
            audioDurationReader: AudioDurationReader = AudioDurationReader.None,
        ): AppServices {
            val graphJob = SupervisorJob()
            try {
                val graphScope = CoroutineScope(graphJob + dispatcher)
                val mediaScope = CoroutineScope(graphJob + mediaSessionDispatcher)
                val memories = KotbaseMemoryService.open(database = database, epochClock = epochClock).fold(
                    ifLeft = { error -> throw IllegalStateException("Memory store failed to open: $error") },
                    ifRight = { it },
                )
                val speakers = KotbaseSpeakerService.open(database = database, epochClock = epochClock).fold(
                    ifLeft = { error -> throw IllegalStateException("Speaker store failed to open: $error") },
                    ifRight = { it },
                )
                val appSettings = KotbaseAppSettingsService.open(database).fold(
                    ifLeft = { error -> throw IllegalStateException("Settings store failed to open: $error") },
                    ifRight = { it },
                )
                onSettingsReady(appSettings)
                val activityTracker = BackgroundActivityTracker()
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
                // The summary job is built after the media session, so the drain reaches it through this holder.
                val callbackGroup = Job(graphJob)
                var runSummaryRecovery: () -> Unit = {}
                var runCategorizationRecovery: () -> Unit = {}
                // The speaker runner needs the media session, which is built after the transcription runner.
                var onSpeakerTranscriptionSucceeded: suspend (String) -> Unit = {}
                val transcriptionRunner = TranscriptionWorkRunner(
                    memoryService = memories,
                    recordingsRoot = recordingsRoot,
                    batchTranscriber = batchTranscriber,
                    scheduler = transcriptionScheduler,
                    onTranscriptionFinished = {
                        runSummaryRecovery()
                        runCategorizationRecovery()
                    },
                    onTranscriptionSucceeded = { memoryId -> onSpeakerTranscriptionSucceeded(memoryId) },
                    activityTracker = activityTracker,
                )
                val mediaSession = RecordingMediaSessionService(
                    memoryService = memories,
                    recorder = recorder,
                    audioPlayer = audioPlayer,
                    microphonePermission = microphonePermission,
                    recordingsRoot = recordingsRoot,
                    scope = mediaScope,
                    epochClock = epochClock,
                    liveTranscriber = liveTranscriber,
                    transcriptionScheduler = transcriptionScheduler,
                    durationReader = audioDurationReader,
                )
                val speakerRunner = SpeakerAnalysisRunner(
                    memoryService = memories,
                    speakerService = speakers,
                    analyzer = speakerAnalyzer,
                    recordingsRoot = recordingsRoot,
                    isRecording = { mediaSession.isBusy },
                    scope = graphScope,
                    activityTracker = activityTracker,
                )
                onSpeakerTranscriptionSucceeded = speakerRunner::onTranscriptionSucceeded
                val reconcilerJob = RecordingReconciler(
                    memoryService = memories,
                    recordingsRoot = recordingsRoot,
                    isRecording = { mediaSession.isBusy },
                    epochClock = epochClock,
                    transcriptionScheduler = transcriptionScheduler,
                    activityTracker = activityTracker,
                ).start(scope = graphScope)
                val purgerJob = DeletedMemoryPurger(
                    memoryService = memories,
                    recordingsRoot = recordingsRoot,
                    isRecording = { mediaSession.isBusy },
                    speakerService = speakers,
                    activityTracker = activityTracker,
                ).start(scope = graphScope)
                // Recovers transcriptions left unfinished by an earlier run, such as a crash or a failed attempt.
                transcriptionScheduler.enqueueSweep()
                val summaryRecovery = MemorySummaryRecoveryJob(
                    memoryService = memories,
                    summarizer = summarizer,
                    isRecording = { mediaSession.isBusy },
                    activityTracker = activityTracker,
                )
                val summaryRecoveryJob = summaryRecovery.start(scope = graphScope)
                runSummaryRecovery = { graphScope.launch(callbackGroup) { summaryRecovery.recover() } }
                val categorizer: MemoryCategorizer = categoryModel
                    ?.let { DefaultMemoryCategorizer(memoryService = memories, model = it) }
                    ?: UnavailableMemoryCategorizer
                val categorizationRecovery = MemoryCategorizationRecoveryJob(
                    memoryService = memories,
                    categorizer = categorizer,
                    isRecording = { mediaSession.isBusy },
                    activityTracker = activityTracker,
                )
                val categorizationRecoveryJob = categorizationRecovery.start(scope = graphScope)
                runCategorizationRecovery = {
                    graphScope.launch(callbackGroup) { categorizationRecovery.recover() }
                }
                // One drain at launch, started last so a failure while wiring the graph cannot leave it running. Its first
                // step requeues analyses that an earlier run left unfinished.
                speakerRunner.requestDrain()
                val speakerSettingsJob = speakerRunner.watchSettings()
                return AppServices(
                    modelSetup = modelSetup,
                    memories = memories,
                    speakers = speakers,
                    speakerAnalyzer = speakerAnalyzer,
                    mediaSession = mediaSession,
                    transcriptionRunner = transcriptionRunner,
                    graphJob = graphJob,
                    closeScope = graphScope,
                    backgroundJobs = listOf(
                        reconcilerJob,
                        purgerJob,
                        summaryRecoveryJob,
                        categorizationRecoveryJob,
                        speakerSettingsJob,
                    ),
                    callbackGroup = callbackGroup,
                    summarizer = summarizer,
                    closeModelRuntime = closeModelRuntime,
                    shutdownSpeakerAnalysis = speakerRunner::shutdown,
                    summaryModelMissing = summaryModelMissingNotice(summaryRecovery.summaryModelMissing, modelSetup.state)
                        .stateIn(graphScope, SharingStarted.Eagerly, false),
                    appSettings = appSettings,
                    backgroundActivities = combine(activityTracker.activities, modelSetup.state) { tracked, model ->
                        activeBackgroundActivities(tracked, model)
                    }.stateIn(graphScope, SharingStarted.Eagerly, emptySet()),
                    transcriptionProgress = activityTracker.transcriptionProgress,
                    onClose = { database.close() },
                )
            } catch (failure: Throwable) {
                graphJob.cancel()
                database.close()
                // Same order as close(): the summarizer stops before the shared runtime it uses is released.
                try {
                    summarizer.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                try {
                    closeModelRuntime()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
    }
}
