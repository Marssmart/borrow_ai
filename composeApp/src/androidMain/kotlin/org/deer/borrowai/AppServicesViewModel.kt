package org.deer.borrowai

import android.content.Context
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
import org.deer.borrowai.data.service.speech.createLiveTranscriber
import org.deer.borrowai.data.service.summary.createSummarizer
import org.deer.borrowai.data.service.recording.AudioPlayer
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.MicrophonePermission
import org.deer.borrowai.data.service.recording.TranscriptionRunnerHolder
import org.deer.borrowai.data.service.recording.WorkManagerTranscriptionScheduler

internal class AppServicesViewModel(
    private val appContext: Context,
    private val modelDevice: ModelDevice,
    private val epochClock: EpochClock,
    private val recordingsRoot: Path,
    private val recorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val microphonePermission: MicrophonePermission,
    private val createServices: suspend () -> AppServices = {
        // The transcriber resolves model files when recording starts, so creation stays free of file IO.
        val liveTranscriber = createLiveTranscriber(
            modelDirectory = modelDirectoryUnder(recordingsRoot.toString()),
            totalRamBytes = modelDevice.totalRamBytes,
        )
        AppServices.create(
            modelDevice = modelDevice,
            epochClock = epochClock,
            recordingsRoot = recordingsRoot,
            recorder = recorder,
            audioPlayer = audioPlayer,
            microphonePermission = microphonePermission,
            liveTranscriber = liveTranscriber,
            batchTranscriber = LazyBatchTranscriber(modelDirectoryUnder(recordingsRoot.toString()), modelDevice.totalRamBytes),
            transcriptionScheduler = WorkManagerTranscriptionScheduler(appContext),
            summarizer = createSummarizer(
                modelDirectory = modelDirectoryUnder(recordingsRoot.toString()),
                totalRamBytes = modelDevice.totalRamBytes,
                cacheDirectory = modelDirectoryUnder(recordingsRoot.toString()).toString(),
            ),
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
                TranscriptionRunnerHolder.runner = created.transcriptionRunner
                servicesState.value = created
            }
        }
    }

    override fun onCleared() {
        cleared = true
        // Workers must stop using the runner before the database closes under it.
        TranscriptionRunnerHolder.runner = null
        servicesState.value?.close()
        modelDevice.close()
        super.onCleared()
    }
}

internal class AppServicesViewModelFactory(
    private val appContext: Context,
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
                appContext = appContext,
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
