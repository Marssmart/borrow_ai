package org.deer.borrowai

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okio.FileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.recording.AudioPlayer
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.MicrophonePermission

@OptIn(ExperimentalCoroutinesApi::class)
class AppServicesViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun serviceGraphSurvivesProviderRecreationAndClosesWithItsStore() {
        val store = ViewModelStore()
        val factory = AppServicesViewModelFactory(
            startupModeFlag = "demo",
            epochClock = EpochClock { 1_800_000_000_000L },
            recordingsRoot = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "borrowai-vm-test",
            recorder = FakeRecorder(),
            audioPlayer = FakeAudioPlayer(),
            microphonePermission = FakeMicrophonePermission(),
        )
        val first = ViewModelProvider(store, factory).get(AppServicesViewModel::class.java)
        val services = assertNotNull(first.services.value)
        val recreatedActivityViewModel = ViewModelProvider(store, factory)
            .get(AppServicesViewModel::class.java)

        assertSame(first, recreatedActivityViewModel)
        assertSame(services, recreatedActivityViewModel.services.value)

        services.mediaSession.startRecording()
        store.clear()

        assertEquals(MediaSessionState.Idle, services.mediaSession.state.value)
        assertFailsWith<IllegalStateException> {
            services.mediaSession.startRecording()
        }
    }

    private class FakeRecorder : AudioRecorder {
        override fun start(absolutePath: String) = Unit
        override fun stop() = Unit
    }

    private class FakeAudioPlayer : AudioPlayer {
        override fun play(absolutePath: String): Long = 1_000L
        override fun stop() = Unit
    }

    private class FakeMicrophonePermission : MicrophonePermission {
        override suspend fun request(): Boolean = true
    }
}
