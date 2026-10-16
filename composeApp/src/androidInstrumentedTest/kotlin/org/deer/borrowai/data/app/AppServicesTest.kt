package org.deer.borrowai.data.app

import kotbase.Database
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import okio.Path
import okio.Path.Companion.toPath
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.model.DownloadingModelSetupService
import org.deer.borrowai.data.service.model.ModelDevice
import org.deer.borrowai.data.service.model.ModelNetworkPolicy
import org.deer.borrowai.data.service.recording.AudioPlayer
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.MicrophonePermission

class AppServicesTest {
    private var database: Database? = null
    private val databaseName = "app-services-test-${System.nanoTime()}"
    private val root: Path = "/data/local/tmp/$databaseName".toPath()

    @BeforeTest
    fun setUp() {
        database = Database(databaseName)
    }

    @AfterTest
    fun tearDown() {
        database = null
    }

    @Test
    fun realGraphWiresDownloadingModelSetupAndCloses() = runTest {
        val services = AppServices.createReal(
            database = requireNotNull(database),
            recordingsRoot = root,
            recorder = object : AudioRecorder {
                override fun start(absolutePath: String) = Unit
                override fun stop() = Unit
            },
            audioPlayer = object : AudioPlayer {
                override fun play(absolutePath: String): Long = 0L
                override fun stop() = Unit
            },
            microphonePermission = object : MicrophonePermission {
                override suspend fun request(): Boolean = false
            },
            modelDevice = FakeModelDevice(),
            epochClock = EpochClock { 1_800_000_000_000L },
        )

        assertIs<DownloadingModelSetupService>(services.modelSetup)
        services.close()
    }

    private class FakeModelDevice(
        override val totalRamBytes: Long = 8L * 1024 * 1024 * 1024,
    ) : ModelDevice {
        private val unmeteredState = MutableStateFlow(false)
        override val network: ModelNetworkPolicy = object : ModelNetworkPolicy {
            override val unmetered: StateFlow<Boolean> = unmeteredState.asStateFlow()
        }
    }
}
