package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemoryDraft
import org.deer.borrowai.data.domain.TranscriptionStatus
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.PlaybackError
import org.deer.borrowai.data.service.PlaybackRequest
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.RecordingError
import org.deer.borrowai.data.service.FakeMemoryService

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingMediaSessionServiceTest {
    private val root = "/app/files".toPath()
    private val recordingFile = "/app/files/recordings/rec-1.m4a".toPath()
    private val clipFile = "/app/files/recordings/clip.m4a".toPath()

    private class FakeRecorder(
        private val failOnStart: Boolean = false,
        private val failOnStop: Boolean = false,
        private val fileSystem: FileSystem? = null,
        private val failOnOutputFile: Boolean = false,
    ) : AudioRecorder {
        val startedPaths = mutableListOf<String>()
        var stopCount = 0

        override fun start(absolutePath: String) {
            if (failOnStart) throw RecordingUnavailableException("no mic")
            if (failOnOutputFile) throw IOException("cannot create output file")
            startedPaths += absolutePath
            // Real recorders create the output file on start, so promotion has something to move.
            fileSystem?.write(absolutePath.toPath()) { writeUtf8("audio") }
        }

        override fun stop() {
            stopCount++
            if (failOnStop) throw RecordingUnavailableException("finalize failed")
        }
    }

    private class FakePermission(private val granted: CompletableDeferred<Boolean>) : MicrophonePermission {
        override val denial = MutableStateFlow<MicrophoneDenial?>(null)
        var openSettingsCount = 0

        override suspend fun request(): Boolean = granted.await()

        override fun openSettings() {
            openSettingsCount++
        }
    }

    private fun memories() = FakeMemoryService(
        initialMemories = emptyList(),
        epochClock = EpochClock { 1_800_000_000_000L },
    )

    /**
     * Plays like a real player: while playing, the position follows [clockMillis], and the player stops itself at the
     * end of the file. With [controlsAvailable] false it reports no position and ignores pause and seek, like iOS.
     */
    private class FakeAudioPlayer(
        private val durationMillis: Long = 2_000L,
        var failOnPlay: Boolean = false,
        var failOnResume: Boolean = false,
        override val controlsAvailable: Boolean = true,
        private val clockMillis: () -> Long = { 0L },
    ) : AudioPlayer {
        val playedPaths = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        var stopCount = 0
        var pauseCount = 0
        var resumeCount = 0
        private var playing = false
        private var positionAtAnchor = 0L
        private var anchorMillis = 0L

        override fun play(absolutePath: String): Long {
            if (failOnPlay) throw PlaybackUnavailableException("cannot play")
            playedPaths += absolutePath
            playing = true
            positionAtAnchor = 0L
            anchorMillis = clockMillis()
            return durationMillis
        }

        override fun stop() {
            stopCount++
            playing = false
        }

        override fun pause() {
            if (!controlsAvailable) return
            if (!playing) return
            positionAtAnchor = currentPosition()
            playing = false
            pauseCount++
        }

        override fun resume() {
            if (failOnResume) throw PlaybackUnavailableException("focus held by another app")
            if (!controlsAvailable || playing) return
            playing = true
            anchorMillis = clockMillis()
            resumeCount++
        }

        override fun seekTo(positionMillis: Long) {
            if (!controlsAvailable) return
            seeks += positionMillis
            positionAtAnchor = positionMillis.coerceIn(0L, durationMillis)
            anchorMillis = clockMillis()
        }

        override fun positionMillis(): Long? = if (controlsAvailable) currentPosition() else null

        private fun currentPosition(): Long {
            if (playing) {
                val position = positionAtAnchor + clockMillis() - anchorMillis
                if (position < durationMillis) return position
                playing = false
                positionAtAnchor = durationMillis
            }
            return positionAtAnchor
        }
    }

    @Test
    fun closeDuringRecorderStartRollsBackCapture() = runTest {
        val fs = FakeFileSystem()
        lateinit var session: RecordingMediaSessionService
        var stopCount = 0
        val recorder = object : AudioRecorder {
            override fun start(absolutePath: String) {
                fs.write(absolutePath.toPath()) { writeUtf8("audio") }
                session.close()
            }

            override fun stop() {
                stopCount++
            }
        }
        val memories = memories()
        session = service(
            recorder = recorder,
            permission = FakePermission(CompletableDeferred(true)),
            memories = memories,
            fileSystem = fs,
            scope = this,
        )

        session.startRecording(holdToRecord = false)
        runCurrent()

        assertEquals(1, stopCount)
        assertFalse(fs.exists("/app/files/recordings/temp_rec-1.m4a".toPath()))
        assertTrue(memories.findById("rec-1").isLeft())
        assertIs<MediaSessionState.Idle>(session.state.value)
    }

    @Test
    fun microphoneDenialReflectsPermissionAndOpenSettingsDelegates() = runTest {
        val permission = FakePermission(CompletableDeferred(false))
        val service = service(FakeRecorder(), permission, memories(), FakeFileSystem(), backgroundScope)

        assertNull(service.microphoneDenial.value)

        permission.denial.value = MicrophoneDenial.PERMANENTLY_DENIED
        assertEquals(MicrophoneDenial.PERMANENTLY_DENIED, service.microphoneDenial.value)

        service.openMicrophoneSettings()
        assertEquals(1, permission.openSettingsCount)
    }

    private fun service(
        recorder: AudioRecorder,
        permission: MicrophonePermission,
        memories: MemoryService,
        fileSystem: FileSystem,
        scope: CoroutineScope,
        audioPlayer: AudioPlayer = FakeAudioPlayer(),
        epochClock: EpochClock = EpochClock { 1_800_000_000_000L },
        monotonicMillis: () -> Long = { 0L },
        liveTranscriber: LiveTranscriber? = null,
        scheduler: TranscriptionScheduler = TranscriptionScheduler.None,
    ) = RecordingMediaSessionService(
        memoryService = memories,
        recorder = recorder,
        audioPlayer = audioPlayer,
        microphonePermission = permission,
        recordingsRoot = root,
        scope = scope,
        epochClock = epochClock,
        fileSystem = fileSystem,
        idGenerator = { "rec-1" },
        monotonicMillis = monotonicMillis,
        liveTranscriber = liveTranscriber,
        transcriptionScheduler = scheduler,
    )

    private class FakeScheduler : TranscriptionScheduler {
        var transcriptionRequests = 0

        override fun enqueueTranscription() {
            transcriptionRequests++
        }

        override fun enqueueSweep() = Unit
    }

    @Test
    fun finishedRecordingSavesPendingTranscriptionAndSchedulesIt() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val scheduler = FakeScheduler()
        val service = service(
            FakeRecorder(fileSystem = fs),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
            scheduler = scheduler,
        )

        service.startRecording()
        runCurrent()
        assertNotNull(service.stopRecording())
        service.awaitPendingWork()

        val saved = memories.getState().value.single()
        assertEquals("", saved.transcription)
        assertEquals(TranscriptionStatus.PENDING, saved.transcriptionStatus)
        assertEquals("recordings/rec-1.m4a", saved.recordingPath)
        assertEquals(1, scheduler.transcriptionRequests)
        assertTrue(fs.exists(recordingFile))
    }

    private class FakeLiveTranscriber : LiveTranscriber {
        var starts = 0
        var stops = 0
        private var onPartial: ((String) -> Unit)? = null
        private var onFailure: ((LiveTranscriptionFailure) -> Unit)? = null

        override fun start(onPartial: (String) -> Unit, onFailure: (LiveTranscriptionFailure) -> Unit) {
            starts++
            this.onPartial = onPartial
            this.onFailure = onFailure
        }

        override fun stop() {
            stops++
            onPartial = null
            onFailure = null
        }

        fun emit(text: String) {
            onPartial?.invoke(text)
        }

        fun fail(failure: LiveTranscriptionFailure) {
            onFailure?.invoke(failure)
        }
    }

    private object ThrowingLiveTranscriber : LiveTranscriber {
        override fun start(onPartial: (String) -> Unit, onFailure: (LiveTranscriptionFailure) -> Unit) {
            throw IllegalStateException("model missing")
        }

        override fun stop() = Unit
    }

    @Test
    fun livePartialTextIsShownWhileRecordingAndClearedOnStop() = runTest {
        val transcriber = FakeLiveTranscriber()
        val fileSystem = FakeFileSystem()
        val recorder = FakeRecorder(fileSystem = fileSystem)
        val service = service(
            recorder,
            FakePermission(CompletableDeferred(true)),
            memories(),
            fileSystem,
            backgroundScope,
            liveTranscriber = transcriber,
        )

        service.startRecording(holdToRecord = true)
        runCurrent()
        transcriber.emit("hello there")

        assertEquals(1, transcriber.starts)
        assertEquals("hello there", assertIs<MediaSessionState.Recording>(service.state.value).transcription)

        service.stopRecording()
        runCurrent()

        assertEquals(1, transcriber.stops)
        assertIs<MediaSessionState.Idle>(service.state.value)
    }

    @Test
    fun liveTranscriberFailureKeepsRecordingNormally() = runTest {
        val recorder = FakeRecorder()
        val service = service(
            recorder,
            FakePermission(CompletableDeferred(true)),
            memories(),
            FakeFileSystem(),
            backgroundScope,
            liveTranscriber = ThrowingLiveTranscriber,
        )

        service.startRecording(holdToRecord = true)
        runCurrent()

        val recording = assertIs<MediaSessionState.Recording>(service.state.value)
        assertEquals("", recording.transcription)
        assertEquals(LiveTranscriptionFailure.ENGINE_FAILED, recording.liveTranscriptionFailure)
        assertEquals(listOf("/app/files/recordings/temp_rec-1.m4a"), recorder.startedPaths)
    }

    @Test
    fun liveTranscriberReportedFailureIsExposedWhileRecording() = runTest {
        val transcriber = FakeLiveTranscriber()
        val fileSystem = FakeFileSystem()
        val recorder = FakeRecorder(fileSystem = fileSystem)
        val service = service(
            recorder,
            FakePermission(CompletableDeferred(true)),
            memories(),
            fileSystem,
            backgroundScope,
            liveTranscriber = transcriber,
        )

        service.startRecording(holdToRecord = true)
        runCurrent()
        transcriber.fail(LiveTranscriptionFailure.AUDIO_INTERRUPTED)

        val recording = assertIs<MediaSessionState.Recording>(service.state.value)
        assertEquals(LiveTranscriptionFailure.AUDIO_INTERRUPTED, recording.liveTranscriptionFailure)
        assertEquals(listOf("/app/files/recordings/temp_rec-1.m4a"), recorder.startedPaths)

        service.stopRecording()
        runCurrent()

        assertIs<MediaSessionState.Idle>(service.state.value)
    }

    @Test
    fun recordingAndPlaybackAvailableWithoutModel() = runTest {
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories(), FakeFileSystem(), backgroundScope)

        assertTrue(service.recordingAvailable)
        assertTrue(service.playbackAvailable)
    }

    @Test
    fun unusableRecordingsFolderKeepsRecorderStopped() = runTest {
        val recorder = FakeRecorder()
        val failingFs = object : ForwardingFileSystem(FakeFileSystem()) {
            override fun createDirectory(dir: Path, mustCreate: Boolean) {
                throw IOException("recordings folder is unusable")
            }
        }
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories(), failingFs, backgroundScope)

        service.startRecording(holdToRecord = true)
        runCurrent()

        assertTrue(recorder.startedPaths.isEmpty())
        assertEquals(MediaSessionState.Failed(RecordingError.SaveFailed), service.state.value)
        assertNull(service.stopRecording())
    }

    @Test
    fun saveFailureDuringConfirmedPlaybackKeepsFailureAndDoesNotPlay() = runTest {
        val player = FakeAudioPlayer()
        val fs = FakeFileSystem()
        val recorder = FakeRecorder(failOnStop = true, fileSystem = fs)
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.startRecording()
        runCurrent()
        val target = PlaybackTarget(memory.id, PlaybackContent.RECORDING)
        assertEquals(PlaybackRequest.ConfirmationRequired(target).right(), service.requestPlayback(target))
        assertEquals(PlaybackError.RecordingUnavailable.left(), service.confirmPendingPlayback())
        runCurrent()

        assertEquals(MediaSessionState.Failed(RecordingError.SaveFailed), service.state.value)
        assertTrue(player.playedPaths.isEmpty())
    }

    @Test
    fun playbackRequestWhileSaveFailureIsShownKeepsFailureAndDoesNotPlay() = runTest {
        val player = FakeAudioPlayer()
        val fs = FakeFileSystem()
        val recorder = FakeRecorder(failOnStop = true, fileSystem = fs)
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.startRecording()
        runCurrent()
        assertNull(service.stopRecording())
        assertEquals(MediaSessionState.Failed(RecordingError.SaveFailed), service.state.value)

        val target = PlaybackTarget(memory.id, PlaybackContent.RECORDING)
        assertEquals(PlaybackError.RecordingUnavailable.left(), service.requestPlayback(target))

        assertEquals(MediaSessionState.Failed(RecordingError.SaveFailed), service.state.value)
        assertTrue(player.playedPaths.isEmpty())
    }

    @Test
    fun cleanupFailureAfterFinalizeFailureStillPublishesSaveFailure() = runTest {
        val base = FakeFileSystem()
        val failingDeleteFs = object : ForwardingFileSystem(base) {
            override fun delete(path: Path, mustExist: Boolean) {
                throw IOException("storage became read-only")
            }
        }
        val recorder = FakeRecorder(failOnStop = true, fileSystem = base)
        val memories = memories()
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories, failingDeleteFs, backgroundScope)

        service.startRecording()
        runCurrent()
        assertNull(service.stopRecording())

        assertEquals(MediaSessionState.Failed(RecordingError.SaveFailed), service.state.value)
        assertTrue(memories.getState().value.isEmpty())
    }

    @Test
    fun promotionFailureAfterStopPublishesSaveFailureAndCreatesNoMemory() = runTest {
        val base = FakeFileSystem()
        val failingMoveFs = object : ForwardingFileSystem(base) {
            override fun atomicMove(source: Path, target: Path) {
                throw IOException("promotion failed")
            }
        }
        val recorder = FakeRecorder(fileSystem = base)
        val memories = memories()
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories, failingMoveFs, backgroundScope)

        service.startRecording()
        runCurrent()
        assertNull(service.stopRecording())
        runCurrent()

        assertEquals(MediaSessionState.Failed(RecordingError.SaveFailed), service.state.value)
        assertTrue(memories.getState().value.isEmpty())
    }

    @Test
    fun grantedPermissionStartsRecordingIntoRecordingsFolder() = runTest {
        val recorder = FakeRecorder()
        val fs = FakeFileSystem()
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories(), fs, backgroundScope)

        service.startRecording(holdToRecord = true)
        runCurrent()

        assertTrue(assertIs<MediaSessionState.Recording>(service.state.value).holdToRecord)
        assertEquals(listOf("/app/files/recordings/temp_rec-1.m4a"), recorder.startedPaths)
        assertTrue(fs.exists("/app/files/recordings".toPath()))
    }

    @Test
    fun deniedPermissionKeepsServiceIdleAndNeverStartsRecorder() = runTest {
        val recorder = FakeRecorder()
        val service = service(recorder, FakePermission(CompletableDeferred(false)), memories(), FakeFileSystem(), backgroundScope)

        service.startRecording()
        runCurrent()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertTrue(recorder.startedPaths.isEmpty())
    }

    @Test
    fun stoppingBeforePermissionGrantCancelsPendingStart() = runTest {
        val recorder = FakeRecorder()
        val granted = CompletableDeferred<Boolean>()
        val service = service(recorder, FakePermission(granted), memories(), FakeFileSystem(), backgroundScope)

        service.startRecording()
        runCurrent()
        assertNull(service.stopRecording())
        granted.complete(true)
        runCurrent()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertTrue(recorder.startedPaths.isEmpty())
    }

    @Test
    fun stopAutoSavesMemoryWithRelativeRecordingPath() = runTest {
        val fs = FakeFileSystem()
        val recorder = FakeRecorder(fileSystem = fs)
        val memories = memories()
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope)

        service.startRecording()
        runCurrent()
        val result = assertNotNull(service.stopRecording())
        runCurrent()

        assertEquals(0L, result.elapsedMillis)
        assertEquals(1, recorder.stopCount)
        assertIs<MediaSessionState.Idle>(service.state.value)
        val saved = memories.getState().value.single()
        assertEquals("recordings/rec-1.m4a", saved.recordingPath)
        assertEquals("", saved.title)
    }

    @Test
    fun outputFileFailureReportsSaveErrorNotMicrophoneWithoutSavingMemory() = runTest {
        val memories = memories()
        val fs = FakeFileSystem()
        val service = service(
            FakeRecorder(failOnOutputFile = true),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
        )

        service.startRecording()
        runCurrent()

        assertEquals(MediaSessionState.Failed(RecordingError.SaveFailed), service.state.value)
        assertTrue(memories.getState().value.isEmpty())
        assertFalse(fs.exists("/app/files/recordings/temp_rec-1.m4a".toPath()))
    }

    @Test
    fun recorderStartFailureReportsMicrophoneErrorWithoutSavingMemory() = runTest {
        val memories = memories()
        val fs = FakeFileSystem()
        val service = service(
            FakeRecorder(failOnStart = true),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
        )

        service.startRecording()
        runCurrent()

        assertEquals(MediaSessionState.Failed(RecordingError.MicrophoneUnavailable), service.state.value)
        assertTrue(memories.getState().value.isEmpty())
        assertFalse(fs.exists(recordingFile))
    }

    @Test
    fun finalizeFailureDiscardsPartialFileAndReportsSaveError() = runTest {
        val memories = memories()
        val fs = FakeFileSystem()
        val service = service(
            FakeRecorder(failOnStop = true),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
        )

        service.startRecording()
        runCurrent()
        fs.write(recordingFile) { writeUtf8("partial") }
        assertNull(service.stopRecording())
        runCurrent()

        assertEquals(MediaSessionState.Failed(RecordingError.SaveFailed), service.state.value)
        assertTrue(memories.getState().value.isEmpty())
        assertFalse(fs.exists(recordingFile))
    }

    @Test
    fun dismissRecordingErrorReturnsToIdleAndIsNotBusy() = runTest {
        val memories = memories()
        val fs = FakeFileSystem()
        val service = service(
            FakeRecorder(failOnStart = true),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
        )

        service.startRecording()
        runCurrent()
        assertFalse(service.isBusy)

        service.dismissRecordingError()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertFalse(service.isBusy)
    }

    @Test
    fun stopWhenIdleReturnsNullAndSavesNothing() = runTest {
        val memories = memories()
        val recorder = FakeRecorder()
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories, FakeFileSystem(), backgroundScope)

        assertNull(service.stopRecording())
        runCurrent()

        assertEquals(0, recorder.stopCount)
        assertTrue(memories.getState().value.isEmpty())
    }

    @Test
    fun failedMemorySaveKeepsRecordingFileAndDoesNotThrow() = runTest {
        val fs = FakeFileSystem()
        val recorder = FakeRecorder(fileSystem = fs)
        val failingMemories = object : MemoryService by memories() {
            override suspend fun create(
                draft: MemoryDraft,
                id: String?,
                transcriptionStatus: TranscriptionStatus,
            ): Either<MemoryError, Memory> = MemoryError.Persistence(IOException("disk full")).left()
        }
        val service = service(recorder, FakePermission(CompletableDeferred(true)), failingMemories, fs, backgroundScope)

        service.startRecording()
        runCurrent()
        fs.write(recordingFile) { writeUtf8("audio") }
        service.stopRecording()
        service.awaitPendingWork()

        assertTrue(fs.exists(recordingFile))
        assertIs<MediaSessionState.Idle>(service.state.value)
    }

    @Test
    fun awaitPendingWorkWaitsForInFlightSave() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delegate = memories()
        val slowMemories = object : MemoryService by delegate {
            override suspend fun create(
                draft: MemoryDraft,
                id: String?,
                transcriptionStatus: TranscriptionStatus,
            ): Either<MemoryError, Memory> {
                gate.await()
                return delegate.create(draft, id, transcriptionStatus)
            }
        }
        val fs = FakeFileSystem()
        val service = service(FakeRecorder(fileSystem = fs), FakePermission(CompletableDeferred(true)), slowMemories, fs, backgroundScope)

        service.startRecording()
        runCurrent()
        service.stopRecording()
        val waiter = async { service.awaitPendingWork() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        gate.complete(Unit)
        waiter.await()
        assertEquals(1, delegate.getState().value.size)
    }

    @Test
    fun requestPlaybackPlaysRecordingFromRecordingsRoot() = runTest {
        val player = FakeAudioPlayer(durationMillis = 5_000L)
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        val result = service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))

        assertEquals(PlaybackRequest.Started.right(), result)
        assertEquals(listOf(clipFile.toString()), player.playedPaths)
        val playing = assertIs<MediaSessionState.Playing>(service.state.value)
        assertEquals(5_000L, playing.durationMillis)
    }

    @Test
    fun textTargetsReturnRecordingUnavailableWithoutPlaying() = runTest {
        val player = FakeAudioPlayer()
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        assertEquals(
            PlaybackError.RecordingUnavailable.left(),
            service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.TRANSCRIPTION)),
        )
        assertTrue(player.playedPaths.isEmpty())
    }

    @Test
    fun missingRecordingFileReturnsRecordingUnavailable() = runTest {
        val player = FakeAudioPlayer()
        val memories = memories()
        val memory = memories.create(MemoryDraft(title = "", summary = "", transcription = "", recordingPath = "recordings/gone.m4a")).getOrNull()!!
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, FakeFileSystem(), backgroundScope, player)

        assertEquals(
            PlaybackError.RecordingUnavailable.left(),
            service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING)),
        )
        assertTrue(player.playedPaths.isEmpty())
    }

    @Test
    fun memoryWithoutRecordingPathReturnsRecordingUnavailable() = runTest {
        val memories = memories()
        val memory = memories.create(MemoryDraft(title = "", summary = "", transcription = "")).getOrNull()!!
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, FakeFileSystem(), backgroundScope)

        assertEquals(
            PlaybackError.RecordingUnavailable.left(),
            service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING)),
        )
    }

    @Test
    fun unknownMemoryReturnsMemoryNotFound() = runTest {
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories(), FakeFileSystem(), backgroundScope)

        assertEquals(
            PlaybackError.MemoryNotFound.left(),
            service.requestPlayback(PlaybackTarget("missing", PlaybackContent.RECORDING)),
        )
    }

    @Test
    fun playerFailureReturnsRecordingUnavailableAndStaysIdle() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(
            FakeRecorder(),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
            FakeAudioPlayer(failOnPlay = true),
        )

        assertEquals(
            PlaybackError.RecordingUnavailable.left(),
            service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING)),
        )
        assertIs<MediaSessionState.Idle>(service.state.value)
    }

    @Test
    fun playbackWithoutControlsStopsWhenDurationElapses() = runTest {
        val player = FakeAudioPlayer(durationMillis = 2_000L, controlsAvailable = false)
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(
            FakeRecorder(),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
            player,
            monotonicMillis = { testScheduler.currentTime },
        )

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(10_000L)
        runCurrent()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertEquals(1, player.stopCount)
    }

    @Test
    fun playbackIgnoresWallClockJumps() = runTest {
        val player = FakeAudioPlayer(durationMillis = 2_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        var epochOffsetMillis = 0L
        val service = service(
            FakeRecorder(),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
            player,
            epochClock = EpochClock { testScheduler.currentTime + epochOffsetMillis },
            monotonicMillis = { testScheduler.currentTime },
        )

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(500L)
        epochOffsetMillis = -100_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertIs<MediaSessionState.Playing>(service.state.value).also { assertFalse(it.paused) }

        advanceTimeBy(1_000L)
        runCurrent()
        val ended = assertIs<MediaSessionState.Playing>(service.state.value)
        assertTrue(ended.paused)
        assertEquals(2_000L, ended.elapsedMillis)
    }

    @Test
    fun closeDuringPlayStopsPlaybackAndLeavesIdle() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        lateinit var service: RecordingMediaSessionService
        var stopCount = 0
        val player = object : AudioPlayer {
            override fun play(absolutePath: String): Long {
                service.close()
                return 2_000L
            }

            override fun stop() {
                stopCount++
            }
        }
        service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        runCurrent()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertTrue(stopCount >= 1)
    }

    @Test
    fun failedReplacementPlaybackClearsPlayingState() = runTest {
        val player = FakeAudioPlayer(durationMillis = 2_000L)
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)
        val target = PlaybackTarget(memory.id, PlaybackContent.RECORDING)

        assertEquals(PlaybackRequest.Started.right(), service.requestPlayback(target))
        player.failOnPlay = true

        assertEquals(PlaybackError.RecordingUnavailable.left(), service.requestPlayback(target))
        assertIs<MediaSessionState.Idle>(service.state.value)
    }

    @Test
    fun startRecordingWhilePlayingStopsPlaybackAndRecords() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L)
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(
            FakeRecorder(fileSystem = fs),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
            player,
        )

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        service.startRecording(holdToRecord = false)
        runCurrent()

        assertEquals(1, player.stopCount)
        assertIs<MediaSessionState.Recording>(service.state.value)
    }

    @Test
    fun stopPlaybackStopsPlayerAndReturnsToIdle() = runTest {
        val player = FakeAudioPlayer()
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        service.stopPlayback()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertEquals(1, player.stopCount)
    }

    @Test
    fun playbackRequestWhileRecordingAsksForConfirmationThenStopsAndSavesRecording() = runTest {
        val player = FakeAudioPlayer()
        val fs = FakeFileSystem()
        val recorder = FakeRecorder(fileSystem = fs)
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.startRecording()
        runCurrent()
        val target = PlaybackTarget(memory.id, PlaybackContent.RECORDING)
        assertEquals(PlaybackRequest.ConfirmationRequired(target).right(), service.requestPlayback(target))
        assertEquals(PlaybackRequest.Started.right(), service.confirmPendingPlayback())
        runCurrent()

        assertEquals(1, recorder.stopCount)
        assertEquals(2, memories.getState().value.size)
        assertEquals(listOf(clipFile.toString()), player.playedPaths)
        assertIs<MediaSessionState.Playing>(service.state.value)
    }

    @Test
    fun confirmingPlaybackForDeletedMemoryReturnsMemoryNotFound() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope)

        service.startRecording()
        runCurrent()
        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        memories.delete(memory.id)

        assertEquals(PlaybackError.MemoryNotFound.left(), service.confirmPendingPlayback())
        assertIs<MediaSessionState.Recording>(service.state.value).also {
            assertNull(it.pendingPlayback)
        }
    }

    @Test
    fun cancellingPendingPlaybackKeepsRecording() = runTest {
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope)

        service.startRecording()
        runCurrent()
        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))

        assertTrue(service.cancelPendingPlayback())
        assertNull(assertIs<MediaSessionState.Recording>(service.state.value).pendingPlayback)
        assertEquals(PlaybackError.NoPendingConfirmation.left(), service.confirmPendingPlayback())
    }

    @Test
    fun closeStopsActivePlayback() = runTest {
        val player = FakeAudioPlayer()
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        service.close()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertEquals(1, player.stopCount)
    }

    @Test
    fun playbackPositionComesFromPlayerNotClock() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(
            FakeRecorder(),
            FakePermission(CompletableDeferred(true)),
            memories,
            fs,
            backgroundScope,
            player,
            monotonicMillis = { 0L },
        )

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(249L)
        runCurrent()
        assertEquals(0L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(250L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)

        advanceTimeBy(250L)
        runCurrent()
        assertEquals(500L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
    }

    @Test
    fun pausePlaybackKeepsPositionAndPausesThePlayer() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(1_000L)
        runCurrent()

        service.pausePlayback()
        advanceTimeBy(5_000L)
        runCurrent()

        val paused = assertIs<MediaSessionState.Playing>(service.state.value)
        assertTrue(paused.paused)
        assertEquals(1_000L, paused.elapsedMillis)
        assertEquals(1, player.pauseCount)
        assertTrue(service.playbackControlsAvailable)
    }

    @Test
    fun resumePlaybackContinuesFromThePausedPosition() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(1_000L)
        runCurrent()
        service.pausePlayback()
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(Unit.right(), service.resumePlayback())
        val resumed = assertIs<MediaSessionState.Playing>(service.state.value)
        assertFalse(resumed.paused)
        assertEquals(1_000L, resumed.elapsedMillis)
        assertEquals(1, player.resumeCount)

        advanceTimeBy(250L)
        runCurrent()
        assertEquals(1_250L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
    }

    @Test
    fun resumeFailureKeepsPlaybackPausedAtTheSamePosition() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(1_000L)
        runCurrent()
        service.pausePlayback()
        player.failOnResume = true

        assertEquals(PlaybackError.ResumeFailed.left(), service.resumePlayback())
        val paused = assertIs<MediaSessionState.Playing>(service.state.value)
        assertTrue(paused.paused)
        assertEquals(1_000L, paused.elapsedMillis)
        assertEquals(0, player.resumeCount)
    }

    @Test
    fun stopPlaybackWhilePausedReturnsToIdle() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(1_000L)
        runCurrent()
        service.pausePlayback()
        service.stopPlayback()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertEquals(1, player.stopCount)
    }

    @Test
    fun seekPlaybackMovesPositionAndKeepsPlaying() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(1_000L)
        runCurrent()
        service.seekPlayback(4_000L)

        val seeked = assertIs<MediaSessionState.Playing>(service.state.value)
        assertEquals(4_000L, seeked.elapsedMillis)
        assertFalse(seeked.paused)
        assertEquals(listOf(4_000L), player.seeks)

        advanceTimeBy(250L)
        runCurrent()
        assertEquals(4_250L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
    }

    @Test
    fun seekWhilePausedKeepsPlaybackPaused() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(1_000L)
        runCurrent()
        service.pausePlayback()
        service.seekPlayback(500L)
        advanceTimeBy(2_000L)
        runCurrent()

        val paused = assertIs<MediaSessionState.Playing>(service.state.value)
        assertTrue(paused.paused)
        assertEquals(500L, paused.elapsedMillis)
    }

    @Test
    fun seekAndSkipAreClampedToTheStartAndTheDuration() = runTest {
        val player = FakeAudioPlayer(durationMillis = 40_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(5_000L)
        runCurrent()

        service.seekPlayback(-1L)
        assertEquals(0L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
        service.skipPlayback(-15_000L)
        assertEquals(0L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
        service.skipPlayback(15_000L)
        assertEquals(15_000L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
        service.seekPlayback(99_999L)
        assertEquals(40_000L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
        service.skipPlayback(15_000L)
        assertEquals(40_000L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
    }

    @Test
    fun skipWorksWhilePaused() = runTest {
        val player = FakeAudioPlayer(durationMillis = 40_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(10_000L)
        runCurrent()
        service.pausePlayback()

        service.skipPlayback(15_000L)
        val forward = assertIs<MediaSessionState.Playing>(service.state.value)
        assertTrue(forward.paused)
        assertEquals(25_000L, forward.elapsedMillis)

        service.skipPlayback(-15_000L)
        val back = assertIs<MediaSessionState.Playing>(service.state.value)
        assertTrue(back.paused)
        assertEquals(10_000L, back.elapsedMillis)
    }

    @Test
    fun reachingTheEndPausesAtTheEndWithoutStoppingThePlayer() = runTest {
        val player = FakeAudioPlayer(durationMillis = 2_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(2_000L)
        runCurrent()

        val ended = assertIs<MediaSessionState.Playing>(service.state.value)
        assertTrue(ended.paused)
        assertEquals(2_000L, ended.elapsedMillis)
        assertEquals(0, player.stopCount)
    }

    @Test
    fun playingAgainAfterTheEndStartsFromTheBeginning() = runTest {
        val player = FakeAudioPlayer(durationMillis = 2_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(Unit.right(), service.resumePlayback())
        assertEquals(listOf(0L), player.seeks)
        val restarted = assertIs<MediaSessionState.Playing>(service.state.value)
        assertFalse(restarted.paused)
        assertEquals(0L, restarted.elapsedMillis)

        advanceTimeBy(250L)
        runCurrent()
        assertEquals(250L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
    }

    @Test
    fun resumeFailureAfterTheEndKeepsPlaybackPausedAtTheEnd() = runTest {
        val player = FakeAudioPlayer(durationMillis = 2_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(2_000L)
        runCurrent()
        player.failOnResume = true

        assertEquals(PlaybackError.ResumeFailed.left(), service.resumePlayback())
        val ended = assertIs<MediaSessionState.Playing>(service.state.value)
        assertTrue(ended.paused)
        assertEquals(2_000L, ended.elapsedMillis)
        assertEquals(2_000L, player.positionMillis())
        assertEquals(0, player.resumeCount)

        player.failOnResume = false
        assertEquals(Unit.right(), service.resumePlayback())
        assertEquals(0L, assertIs<MediaSessionState.Playing>(service.state.value).elapsedMillis)
        assertEquals(listOf(0L, 2_000L, 0L), player.seeks)
    }

    @Test
    fun pauseSeekAndSkipAreIgnoredWhenThePlayerHasNoControls() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, controlsAvailable = false)
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(1_000L)
        runCurrent()
        service.pausePlayback()
        service.seekPlayback(5_000L)
        service.skipPlayback(15_000L)

        assertFalse(assertIs<MediaSessionState.Playing>(service.state.value).paused)
        assertEquals(0, player.pauseCount)
        assertTrue(player.seeks.isEmpty())
        assertFalse(service.playbackControlsAvailable)
    }

    @Test
    fun pauseResumeSeekAndSkipAreNoOpsWhenIdle() = runTest {
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories(), FakeFileSystem(), backgroundScope)

        service.pausePlayback()
        service.seekPlayback(1_000L)
        service.skipPlayback(15_000L)

        assertEquals(Unit.right(), service.resumePlayback())
        assertIs<MediaSessionState.Idle>(service.state.value)
    }

    @Test
    fun closeWhilePausedStopsPlayerAndLeavesIdle() = runTest {
        val player = FakeAudioPlayer(durationMillis = 10_000L, clockMillis = { testScheduler.currentTime })
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)

        service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING))
        advanceTimeBy(1_000L)
        runCurrent()
        service.pausePlayback()
        service.close()

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertEquals(1, player.stopCount)
    }

    private suspend fun savedRecording(memories: FakeMemoryService, fs: FakeFileSystem): Memory {
        val memory = memories.create(
            MemoryDraft(title = "", summary = "", transcription = "", recordingPath = "recordings/clip.m4a"),
        ).getOrNull()!!
        fs.createDirectories(root / "recordings")
        fs.write(root / "recordings" / "clip.m4a") { writeUtf8("audio") }
        return memory
    }

}
