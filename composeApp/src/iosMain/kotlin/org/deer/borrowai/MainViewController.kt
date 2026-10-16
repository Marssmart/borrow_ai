package org.deer.borrowai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import org.deer.borrowai.data.app.AppServices
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.model.IosModelDevice
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.recording.IosAudioPlayer
import org.deer.borrowai.data.service.recording.IosAudioRecorder
import org.deer.borrowai.data.service.recording.IosMicrophonePermission
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    // Stored files are relative to this root; the container path can change between installs.
    val recordingsRoot = (NSHomeDirectory() + "/Documents").toPath()
    val services by produceState<AppServices?>(initialValue = null) {
        val modelDevice = IosModelDevice()
        try {
            excludeModelsFromBackup(recordingsRoot.toString())
            val created = AppServices.create(
                modelDevice = modelDevice,
                epochClock = EpochClock {
                    (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
                },
                recordingsRoot = recordingsRoot,
                recorder = IosAudioRecorder(),
                audioPlayer = IosAudioPlayer(),
                microphonePermission = IosMicrophonePermission(),
            )
            value = created
            // Tied to the producer, so the graph closes even if the view leaves composition before a new one is made.
            awaitDispose { created.close() }
        } finally {
            // Runs on cancellation and startup failure too, so the network monitor never outlives the producer.
            modelDevice.close()
        }
    }
    services?.let { App(it) }
}

/** Keeps downloaded models out of iCloud backup; they can be downloaded again. */
@OptIn(ExperimentalForeignApi::class)
private fun excludeModelsFromBackup(appDirectory: String) {
    val url = NSURL.fileURLWithPath("$appDirectory/${ModelCatalog.MODEL_DIRECTORY_NAME}", isDirectory = true)
    NSFileManager.defaultManager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
    url.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
}

private const val MILLIS_PER_SECOND = 1_000.0
