package org.deer.borrowai

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.capture_pause_recording
import borrowai.composeapp.generated.resources.capture_pause_recording_description
import borrowai.composeapp.generated.resources.capture_resume_recording
import borrowai.composeapp.generated.resources.capture_resume_recording_description
import org.jetbrains.compose.resources.stringResource

/** Pause or Resume under the microphone while a tap-to-record capture runs. */
@Composable
internal fun RecordingPauseControl(isPaused: Boolean, onPause: () -> Unit, onResume: () -> Unit) {
    val pauseDescription = stringResource(Res.string.capture_pause_recording_description)
    val resumeDescription = stringResource(Res.string.capture_resume_recording_description)
    if (isPaused) {
        FilledTonalButton(
            onClick = onResume,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).semantics { contentDescription = resumeDescription },
        ) {
            Text(stringResource(Res.string.capture_resume_recording))
        }
    } else {
        OutlinedButton(
            onClick = onPause,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp).semantics { contentDescription = pauseDescription },
        ) {
            Text(stringResource(Res.string.capture_pause_recording))
        }
    }
}
