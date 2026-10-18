package org.deer.borrowai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.Path
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.model.ModelDevice
import org.deer.borrowai.data.service.model.modelDirectoryUnder
import org.deer.borrowai.data.service.speech.LazyBatchTranscriber
import org.deer.borrowai.data.service.speech.LazyLiveTranscriber
import org.deer.borrowai.data.service.recording.AudioPlayer
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.MicrophonePermission

internal class AppServicesViewModel(
    private val modelDevice: ModelDevice,
    private val epochClock: EpochClock,
    private val recordingsRoot: Path,
    private val recorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val microphonePermission: MicrophonePermission,
    private val createServices: suspend () -> AppServices = {
        // Transcribers resolve their model files when first used, so models downloaded after launch are picked up.
        val modelDirectory = modelDirectoryUnder(recordingsRoot.toString())
        AppServices.create(
            modelDevice = modelDevice,
            epochClock = epochClock,
            recordingsRoot = recordingsRoot,
            recorder = recorder,
            audioPlayer = audioPlayer,
            microphonePermission = microphonePermission,
            liveTranscriber = LazyLiveTranscriber(modelDirectory, modelDevice.totalRamBytes),
            batchTranscriber = LazyBatchTranscriber(modelDirectory, modelDevice.totalRamBytes),
        )
    },
) : ViewModel() {
    private val servicesState = MutableStateFlow<AppServices?>(null)
    private var cleared = false

    /** Null until the graph is built, because the database opens asynchronously. */
    val services: StateFlow<AppServices?> = servicesState.asStateFlow()

    init {
        viewModelScope.launch {
            val created = createServices()
            // Creation can finish after onCleared ran; close it here instead of leaking it.
            if (cleared) {
                created.close()
            } else {
                servicesState.value = created
            }
        }
    }

    override fun onCleared() {
        cleared = true
        servicesState.value?.close()
        modelDevice.close()
        super.onCleared()
    }
}

internal class AppServicesViewModelFactory(
    private val createModelDevice: () -> ModelDevice,
    private val epochClock: EpochClock,
    private val recordingsRoot: Path,
    private val recorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val microphonePermission: MicrophonePermission,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass != AppServicesViewModel::class.java) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return modelClass.cast(
            AppServicesViewModel(
                modelDevice = createModelDevice(),
                epochClock = epochClock,
                recordingsRoot = recordingsRoot,
                recorder = recorder,
                audioPlayer = audioPlayer,
                microphonePermission = microphonePermission,
            ),
        )
    }
}
