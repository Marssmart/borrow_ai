package org.deer.borrowai

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_playback_speed
import org.jetbrains.compose.resources.stringResource

/** The speeds the speed menu offers, from slowest to fastest. */
internal val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** A speed as the button and the menu show it, for example "1×" or "1.25×". */
internal fun formatPlaybackSpeed(speed: Float): String = speed.toString().removeSuffix(".0") + "×"

/** The speed button in the playback panel. Its menu lists [PLAYBACK_SPEEDS] and closes after a choice. */
@Composable
internal fun PlaybackSpeedButton(
    speed: Float,
    onSpeedSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = formatPlaybackSpeed(speed)
    val description = stringResource(Res.string.memory_playback_speed, label)
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PLAYBACK_SPEEDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatPlaybackSpeed(option)) },
                    leadingIcon = { RadioButton(selected = option == speed, onClick = null) },
                    modifier = Modifier.semantics { selected = option == speed },
                    onClick = {
                        expanded = false
                        onSpeedSelected(option)
                    },
                )
            }
        }
    }
}
