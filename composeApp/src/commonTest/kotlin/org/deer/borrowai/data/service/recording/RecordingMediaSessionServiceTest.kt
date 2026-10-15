package org.deer.borrowai.data.service.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingMediaSessionServiceTest {
    private val root = "/app/files".toPath()
    private val recordingFile = "/app/files/recordings/rec-1.m4a".toPath()

    private class FakeRecorder(
        private val failOnStart: Boolean = false,
        private val failOnStop: Boolean = false,
    ) : AudioRecorder {
        val startedPaths = mutableListOf<String>()
        var stopCount = 0

        override fun start(absolutePath: String) {
            if (failOnStart) throw RecordingUnavailableException("no mic")
            startedPaths += absolutePath
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

    private fun service(
        recorder: AudioRecorder,
        permission: MicrophonePermission,
        memories: InMemoryDemoMemoryService,
        fileSystem: FakeFileSystem,
        scope: CoroutineScope,
    ) = RecordingMediaSessionService(
        memoryService = memories,
        recorder = recorder,
        microphonePermission = permission,
        recordingsRoot = root,
        scope = scope,
        epochClock = EpochClock { 1_800_000_000_000L },
        fileSystem = fileSystem,
        idGenerator = { "rec-1" },
    )

    @Test
    fun grantedPermissionStartsRecordingIntoRecordingsFolder() = runTest {
        val recorder = FakeRecorder()
        val fs = FakeFileSystem()
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories(), fs, backgroundScope)

        service.startRecording(holdToRecord = true)
        runCurrent()

        assertTrue(assertIs<MediaSessionState.Recording>(service.state.value).holdToRecord)
        assertEquals(listOf(recordingFile.toString()), recorder.startedPaths)
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
        val recorder = FakeRecorder()
        val memories = memories()
        val service = service(recorder, FakePermission(CompletableDeferred(true)), memories, FakeFileSystem(), backgroundScope)

        service.startRecording()
        runCurrent()
        val result = assertNotNull(service.stopRecording())
        runCurrent()

        assertEquals(0L, result.elapsedMillis)
        assertEquals(1, recorder.stopCount)
        assertIs<MediaSessionState.Idle>(service.state.value)
        val saved = memories.memories.value.single()
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
        assertTrue(memories.memories.value.isEmpty())
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
        assertTrue(memories.memories.value.isEmpty())
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
        assertTrue(memories.memories.value.isEmpty())
    }
}
