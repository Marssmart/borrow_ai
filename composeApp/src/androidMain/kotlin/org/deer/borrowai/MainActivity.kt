package org.deer.borrowai

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import okio.Path.Companion.toPath
import org.deer.borrowai.data.app.AppMode
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.recording.ActivityMicrophonePermission
import org.deer.borrowai.data.service.recording.AndroidAudioPlayer
import org.deer.borrowai.data.service.recording.AndroidAudioRecorder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home-screen launches cannot carry the demo extra from the IDE run configuration.
        val startupModeFlag = intent.getStringExtra(DATA_MODE_EXTRA)
            ?: if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                AppMode.DEMO.name
            } else {
                null
            }
        // The permission launcher must be registered before the activity is started.
        val microphonePermission = ActivityMicrophonePermission.register(this)
        val viewModel = ViewModelProvider(
            this,
            AppServicesViewModelFactory(
                startupModeFlag = startupModeFlag,
                epochClock = EpochClock { System.currentTimeMillis() },
                // The service stores "recordings/<id>.m4a" relative to this root.
                recordingsRoot = filesDir.absolutePath.toPath(),
                recorder = AndroidAudioRecorder(applicationContext),
                audioPlayer = AndroidAudioPlayer(),
                microphonePermission = microphonePermission,
            ),
        ).get(AppServicesViewModel::class.java)
        setContent {
            val services by viewModel.services.collectAsState()
            services?.let { App(it) }
        }
    }

    private companion object {
        const val DATA_MODE_EXTRA = "BORROW_AI_DATA_MODE"
    }
}
