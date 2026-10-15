package org.deer.borrowai

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.ComposeUIViewController
import okio.Path.Companion.toPath
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.recording.IosAudioRecorder
import org.deer.borrowai.data.service.recording.IosMicrophonePermission
import platform.Foundation.NSDate
import platform.Foundation.NSHomeDirectory
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIViewController

fun MainViewController(startupModeFlag: String?): UIViewController = ComposeUIViewController {
    // Stored files are relative to this root; the container path can change between installs.
    val recordingsRoot = (NSHomeDirectory() + "/Documents").toPath()
    val services by produceState<AppServices?>(initialValue = null, startupModeFlag) {
        value = AppServices.create(
            startupModeFlag = startupModeFlag,
            epochClock = EpochClock {
                (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
            },
            recordingsRoot = recordingsRoot,
            recorder = IosAudioRecorder(),
            microphonePermission = IosMicrophonePermission(),
        )
    }
    DisposableEffect(services) {
        onDispose { services?.close() }
    }
    services?.let { App(it) }
}

private const val MILLIS_PER_SECOND = 1_000.0
