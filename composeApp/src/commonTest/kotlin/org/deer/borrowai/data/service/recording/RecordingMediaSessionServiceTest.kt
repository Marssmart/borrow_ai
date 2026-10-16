package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.PlaybackContent
import org.deer.borrowai.data.service.PlaybackRequestResult
import org.deer.borrowai.data.service.PlaybackTarget
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingMediaSessionServiceTest {
    private val root = "/app/files".toPath()
    private val recordingFile = "/app/files/recordings/rec-1.m4a".toPath()
    private val clipFile = "/app/files/recordings/clip.m4a".toPath()

    private class FakeRecorder(
        private val failOnStart: Boolean = false,
        private val failOnStop: Boolean = false,
        private val fileSystem: FileSystem? = null,
    ) : AudioRecorder {
        val startedPaths = mutableListOf<String>()
        var stopCount = 0

        override fun start(absolutePath: String) {
            if (failOnStart) throw RecordingUnavailableException("no mic")
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
        override suspend fun request(): Boolean = granted.await()
    }

    private fun memories() = InMemoryDemoMemoryService(
        initialMemories = emptyList(),
        epochClock = EpochClock { 1_800_000_000_000L },
    )

    private class FakeAudioPlayer(
        private val durationMillis: Long = 2_000L,
        var failOnPlay: Boolean = false,
    ) : AudioPlayer {
        val playedPaths = mutableListOf<String>()
        var stopCount = 0

        override fun play(absolutePath: String): Long {
            if (failOnPlay) throw PlaybackUnavailableException("cannot play")
            playedPaths += absolutePath
            return durationMillis
        }

        override fun stop() {
            stopCount++
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
        assertNull(memories.findById("rec-1"))
        assertIs<MediaSessionState.Idle>(session.state.value)
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
    )

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
        assertIs<MediaSessionState.Idle>(service.state.value)
        assertNull(service.stopRecording())
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
    fun recorderStartFailureLeavesServiceIdleWithoutSavingMemory() = runTest {
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

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertTrue(memories.getState().value.isEmpty())
        assertFalse(fs.exists(recordingFile))
    }

    @Test
    fun finalizeFailureDiscardsPartialFileAndSavesNothing() = runTest {
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

        assertIs<MediaSessionState.Idle>(service.state.value)
        assertTrue(memories.getState().value.isEmpty())
        assertFalse(fs.exists(recordingFile))
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
            override suspend fun create(draft: MemoryDraft, id: String?): Memory = error("disk full")
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
            override suspend fun create(draft: MemoryDraft, id: String?): Memory {
                gate.await()
                return delegate.create(draft, id)
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

        assertEquals(PlaybackRequestResult.Started, result)
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
            PlaybackRequestResult.RecordingUnavailable,
            service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.TRANSCRIPTION)),
        )
        assertTrue(player.playedPaths.isEmpty())
    }

    @Test
    fun missingRecordingFileReturnsRecordingUnavailable() = runTest {
        val player = FakeAudioPlayer()
        val memories = memories()
        val memory = memories.create(MemoryDraft(title = "", summary = "", transcription = "", recordingPath = "recordings/gone.m4a"))
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, FakeFileSystem(), backgroundScope, player)

        assertEquals(
            PlaybackRequestResult.RecordingUnavailable,
            service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING)),
        )
        assertTrue(player.playedPaths.isEmpty())
    }

    @Test
    fun memoryWithoutRecordingPathReturnsRecordingUnavailable() = runTest {
        val memories = memories()
        val memory = memories.create(MemoryDraft(title = "", summary = "", transcription = ""))
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, FakeFileSystem(), backgroundScope)

        assertEquals(
            PlaybackRequestResult.RecordingUnavailable,
            service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING)),
        )
    }

    @Test
    fun unknownMemoryReturnsMemoryNotFound() = runTest {
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories(), FakeFileSystem(), backgroundScope)

        assertEquals(
            PlaybackRequestResult.MemoryNotFound,
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
            PlaybackRequestResult.RecordingUnavailable,
            service.requestPlayback(PlaybackTarget(memory.id, PlaybackContent.RECORDING)),
        )
        assertIs<MediaSessionState.Idle>(service.state.value)
    }

    @Test
    fun playbackFinishesWhenDurationElapses() = runTest {
        val player = FakeAudioPlayer(durationMillis = 2_000L)
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
        val player = FakeAudioPlayer(durationMillis = 2_000L)
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
        assertIs<MediaSessionState.Playing>(service.state.value)

        advanceTimeBy(1_000L)
        runCurrent()
        assertIs<MediaSessionState.Idle>(service.state.value)
    }

    @Test
    fun failedReplacementPlaybackClearsPlayingState() = runTest {
        val player = FakeAudioPlayer(durationMillis = 2_000L)
        val fs = FakeFileSystem()
        val memories = memories()
        val memory = savedRecording(memories, fs)
        val service = service(FakeRecorder(), FakePermission(CompletableDeferred(true)), memories, fs, backgroundScope, player)
        val target = PlaybackTarget(memory.id, PlaybackContent.RECORDING)

        assertEquals(PlaybackRequestResult.Started, service.requestPlayback(target))
        player.failOnPlay = true

        assertEquals(PlaybackRequestResult.RecordingUnavailable, service.requestPlayback(target))
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
        assertEquals(PlaybackRequestResult.ConfirmationRequired(target), service.requestPlayback(target))
        assertEquals(PlaybackRequestResult.Started, service.confirmPendingPlayback())
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

        assertEquals(PlaybackRequestResult.MemoryNotFound, service.confirmPendingPlayback())
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
        assertEquals(PlaybackRequestResult.NoPendingConfirmation, service.confirmPendingPlayback())
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

    private suspend fun savedRecording(memories: InMemoryDemoMemoryService, fs: FakeFileSystem): Memory {
        val memory = memories.create(
            MemoryDraft(title = "", summary = "", transcription = "", recordingPath = "recordings/clip.m4a"),
        )
        fs.createDirectories(root / "recordings")
        fs.write(root / "recordings" / "clip.m4a") { writeUtf8("audio") }
        return memory
    }

}
