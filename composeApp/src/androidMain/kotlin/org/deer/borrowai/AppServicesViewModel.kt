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
import org.deer.borrowai.data.service.recording.AudioPlayer
import org.deer.borrowai.data.service.recording.AudioRecorder
import org.deer.borrowai.data.service.recording.MicrophonePermission

internal class AppServicesViewModel(
    private val startupModeFlag: String?,
    private val epochClock: EpochClock,
    private val recordingsRoot: Path,
    private val recorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val microphonePermission: MicrophonePermission,
) : ViewModel() {
    private val servicesState = MutableStateFlow<AppServices?>(null)

    /** Null until the graph is built; REAL mode opens the database asynchronously. */
    val services: StateFlow<AppServices?> = servicesState.asStateFlow()

    init {
        viewModelScope.launch {
            servicesState.value = AppServices.create(
                startupModeFlag = startupModeFlag,
                epochClock = epochClock,
                recordingsRoot = recordingsRoot,
                recorder = recorder,
                audioPlayer = audioPlayer,
                microphonePermission = microphonePermission,
            )
        }
    }

    override fun onCleared() {
        servicesState.value?.close()
        super.onCleared()
    }
}

internal class AppServicesViewModelFactory(
    private val startupModeFlag: String?,
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
                startupModeFlag = startupModeFlag,
                epochClock = epochClock,
                recordingsRoot = recordingsRoot,
                recorder = recorder,
                audioPlayer = audioPlayer,
                microphonePermission = microphonePermission,
            ),
        )
    }
}
