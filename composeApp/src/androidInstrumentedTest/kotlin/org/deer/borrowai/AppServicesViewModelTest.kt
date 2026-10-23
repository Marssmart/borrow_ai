package org.deer.borrowai

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotbase.Database
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionState
import org.deer.borrowai.data.service.model.ModelDevice
import org.deer.borrowai.data.service.model.ModelNetworkPolicy
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
            appContext = InstrumentationRegistry.getInstrumentation().targetContext,
            createModelDevice = { FakeModelDevice() },
            epochClock = EpochClock { 1_800_000_000_000L },
            recordingsRoot = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "borrowai-vm-test",
            recorder = FakeRecorder(),
            audioPlayer = FakeAudioPlayer(),
            microphonePermission = FakeMicrophonePermission(),
        )
        val first = ViewModelProvider(store, factory).get(AppServicesViewModel::class.java)
        val services = runBlocking {
            withTimeout(GRAPH_TIMEOUT_MILLIS) { first.services.filterNotNull().first() }
        }
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

    @Test
    fun servicesFinishingAfterClearAreClosed() {
        val gate = CompletableDeferred<AppServices>()
        val store = ViewModelStore()
        val viewModel = AppServicesViewModel(
            appContext = InstrumentationRegistry.getInstrumentation().targetContext,
            modelDevice = FakeModelDevice(),
            epochClock = EpochClock { 1_800_000_000_000L },
            recordingsRoot = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "borrowai-vm-test",
            recorder = FakeRecorder(),
            audioPlayer = FakeAudioPlayer(),
            microphonePermission = FakeMicrophonePermission(),
            // Creation ignores cancellation, as a graph build that completes after clear would.
            createServices = { withContext(NonCancellable) { gate.await() } },
        )
        store.put("services", viewModel)

        store.clear()
        val late = runBlocking {
            AppServices.createReal(
                database = Database("borrowai-vm-late-${System.nanoTime()}"),
                epochClock = EpochClock { 1_800_000_000_000L },
                recordingsRoot = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "borrowai-vm-test",
                recorder = FakeRecorder(),
                audioPlayer = FakeAudioPlayer(),
                microphonePermission = FakeMicrophonePermission(),
                modelDevice = FakeModelDevice(),
            )
        }
        gate.complete(late)

        assertNull(viewModel.services.value)
        assertFailsWith<IllegalStateException> {
            late.mediaSession.startRecording()
        }
        assertEquals(MediaSessionState.Idle, late.mediaSession.state.value)
    }

    private companion object {
        // The real graph's initial database read runs on Dispatchers.Default, so wait for publication.
        const val GRAPH_TIMEOUT_MILLIS = 10_000L
    }

    private class FakeRecorder : AudioRecorder {
        // Like the real recorder, create the output file so finishing the recording can promote it.
        override fun start(absolutePath: String) {
            FileSystem.SYSTEM.sink(absolutePath.toPath()).use { }
        }
        override fun stop() = Unit
    }

    private class FakeAudioPlayer : AudioPlayer {
        override fun play(absolutePath: String): Long = 1_000L
        override fun stop() = Unit
    }

    private class FakeMicrophonePermission : MicrophonePermission {
        override suspend fun request(): Boolean = true
    }

    private class FakeModelDevice : ModelDevice {
        override val totalRamBytes: Long = 8L * 1024 * 1024 * 1024
        private val unmeteredState = MutableStateFlow(false)
        override val network: ModelNetworkPolicy = object : ModelNetworkPolicy {
            override val unmetered: StateFlow<Boolean> = unmeteredState.asStateFlow()
        }
    }
}
