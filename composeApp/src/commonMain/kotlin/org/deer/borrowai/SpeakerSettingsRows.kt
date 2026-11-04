package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.settings_speaker_models_available
import borrowai.composeapp.generated.resources.settings_speaker_models_not_available
import borrowai.composeapp.generated.resources.settings_speaker_models_title
import borrowai.composeapp.generated.resources.settings_speaker_recognition_description
import borrowai.composeapp.generated.resources.settings_speaker_recognition_title
import org.jetbrains.compose.resources.stringResource

/** The speaker recognition toggle and the model status row in Settings. The caller leaves them out where unsupported. */
@Composable
internal fun SpeakerSettingsRows(
    state: SpeakerSettingsUiState,
    onTurnOff: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.enabled,
                    enabled = state.switchEnabled,
                    role = Role.Switch,
                    onValueChange = { if (!it) onTurnOff() },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = stringResource(Res.string.settings_speaker_recognition_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.settings_speaker_recognition_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.enabled,
                onCheckedChange = null,
                enabled = state.switchEnabled,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_speaker_models_title),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (state.modelAvailable) {
                        Res.string.settings_speaker_models_available
                    } else {
                        Res.string.settings_speaker_models_not_available
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
