package org.deer.borrowai.data.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.FileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.demo.DemoModelSetupService
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.MicrophonePermission
import org.deer.borrowai.data.service.recording.RecordingMediaSessionService

class AppServicesTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class NoopRecorder : AudioRecorder {
        override fun start(absolutePath: String) = Unit
        override fun stop() = Unit
    }

    private class GrantedPermission : MicrophonePermission {
        override suspend fun request(): Boolean = CompletableDeferred(true).await()
    }

    private fun demoServices(dispatcher: kotlinx.coroutines.CoroutineDispatcher? = null): AppServices {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "borrowai-appservices-test"
        return if (dispatcher == null) {
            AppServices.createFromStartupFlag(
                startupModeFlag = "demo",
                epochClock = EpochClock { 1_800_000_000_000L },
                recordingsRoot = root,
                recorder = NoopRecorder(),
                microphonePermission = GrantedPermission(),
            )
        } else {
            AppServices.createFromStartupFlag(
                startupModeFlag = "demo",
                epochClock = EpochClock { 1_800_000_000_000L },
                recordingsRoot = root,
                recorder = NoopRecorder(),
                microphonePermission = GrantedPermission(),
                dispatcher = dispatcher,
            )
        }
    }

    @Test
    fun startupFlagSelectsDemoOnlyForDemo() {
        assertEquals(AppMode.DEMO, AppMode.fromStartupFlag(" demo "))
        assertEquals(AppMode.REAL, AppMode.fromStartupFlag(null))
        assertEquals(AppMode.REAL, AppMode.fromStartupFlag("invalid"))
        assertEquals(AppMode.REAL, AppMode.fromStartupFlag("real"))

        val services = demoServices()
        assertEquals(AppMode.DEMO, services.mode)
        assertNotNull(services.modelSetup as? DemoModelSetupService)
        assertNotNull(services.memories as? InMemoryDemoMemoryService)
        assertIs<RecordingMediaSessionService>(services.mediaSession)
        services.close()
    }

    @Test
    fun realModeFailsInsteadOfFallingBackToDemo() = runTest {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "borrowai-appservices-test"
        for (flag in listOf("real", null)) {
            assertFailsWith<RealServicesUnavailableException> {
                AppServices.createFromStartupFlag(
                    startupModeFlag = flag,
                    epochClock = EpochClock { 1_800_000_000_000L },
                    recordingsRoot = root,
                    recorder = NoopRecorder(),
                    microphonePermission = GrantedPermission(),
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closingServiceGraphStopsMediaSessionWork() = runTest {
        val services = demoServices(StandardTestDispatcher(testScheduler))

        services.mediaSession.startRecording()
        runCurrent()
        assertIs<MediaSessionState.Recording>(services.mediaSession.state.value)

        services.close()
        assertEquals(MediaSessionState.Idle, services.mediaSession.state.value)
        advanceTimeBy(1_000)
        runCurrent()

        assertFailsWith<IllegalStateException> {
            services.mediaSession.startRecording()
        }
        assertEquals(MediaSessionState.Idle, services.mediaSession.state.value)
        services.close()
    }
}
