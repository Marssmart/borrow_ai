package org.deer.borrowai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import okio.Path.Companion.toPath
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.model.AndroidModelDevice
import org.deer.borrowai.data.service.recording.AndroidAudioPlayer
import org.deer.borrowai.data.service.recording.ActivityMicrophonePermission
import org.deer.borrowai.data.service.recording.AndroidAudioRecorder
import org.deer.borrowai.data.service.recording.MicrophonePermissionViewModel

class MainActivity : ComponentActivity() {
    private lateinit var microphonePermission: ActivityMicrophonePermission

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The permission outlives recreation, so each activity re-attaches its launcher before use.
        microphonePermission = ViewModelProvider(this)[MicrophonePermissionViewModel::class.java]
            .permission
            .also { it.attach(this) }
        val viewModel = ViewModelProvider(
            this,
            AppServicesViewModelFactory(
                appContext = applicationContext,
                // Built only when the ViewModel is created, so recreated activities don't register a second callback.
                createModelDevice = { AndroidModelDevice(applicationContext) },
                epochClock = EpochClock { System.currentTimeMillis() },
                // The service stores "recordings/<id>.m4a" relative to this root.
                recordingsRoot = filesDir.absolutePath.toPath(),
                recorder = AndroidAudioRecorder(applicationContext),
                audioPlayer = AndroidAudioPlayer(applicationContext),
                microphonePermission = microphonePermission,
            ),
        ).get(AppServicesViewModel::class.java)
        setContent {
            val services by viewModel.services.collectAsState()
            services?.let { App(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted the microphone in Settings while the app was in the background.
        microphonePermission.refreshDenial()
    }
}
