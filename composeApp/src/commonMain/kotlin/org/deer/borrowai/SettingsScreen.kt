package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.settings_background_indicator_description
import borrowai.composeapp.generated.resources.settings_background_indicator_title
import borrowai.composeapp.generated.resources.settings_gpu_acceleration_description
import borrowai.composeapp.generated.resources.settings_gpu_acceleration_title
import borrowai.composeapp.generated.resources.settings_speaker_manage_speakers
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsScreen(
    showBackgroundActivityIndicator: Boolean,
    onShowBackgroundActivityIndicatorChange: (Boolean) -> Unit,
    useGpuAcceleration: Boolean,
    onUseGpuAccelerationChange: (Boolean) -> Unit,
    speakerSettings: SpeakerSettingsUiState,
    onTurnOnSpeakerRecognition: () -> Unit,
    onTurnOffSpeakerRecognition: () -> Unit,
    onAgreeToSpeakerConsent: () -> Unit,
    onDeclineSpeakerConsent: () -> Unit,
    onDownloadSpeakerModels: () -> Unit,
    onDownloadSpeakerModelsOverMobileData: () -> Unit,
    onRequestSpeakerWithdrawal: () -> Unit,
    onConfirmSpeakerWithdrawal: () -> Unit,
    onCancelSpeakerWithdrawal: () -> Unit,
    onManageSpeakers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = showBackgroundActivityIndicator,
                    role = Role.Switch,
                    onValueChange = onShowBackgroundActivityIndicatorChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = stringResource(Res.string.settings_background_indicator_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.settings_background_indicator_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = showBackgroundActivityIndicator,
                onCheckedChange = null,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = useGpuAcceleration,
                    role = Role.Switch,
                    onValueChange = onUseGpuAccelerationChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = stringResource(Res.string.settings_gpu_acceleration_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.settings_gpu_acceleration_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = useGpuAcceleration,
                onCheckedChange = null,
            )
        }
        if (speakerRecognitionSupported) {
            TextButton(
                onClick = onManageSpeakers,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.settings_speaker_manage_speakers))
            }
            SpeakerSettingsRows(
                state = speakerSettings,
                onTurnOn = onTurnOnSpeakerRecognition,
                onTurnOff = onTurnOffSpeakerRecognition,
                onAgreeToConsent = onAgreeToSpeakerConsent,
                onDeclineConsent = onDeclineSpeakerConsent,
                onDownloadModels = onDownloadSpeakerModels,
                onDownloadOverMobileData = onDownloadSpeakerModelsOverMobileData,
                onRequestWithdrawal = onRequestSpeakerWithdrawal,
                onConfirmWithdrawal = onConfirmSpeakerWithdrawal,
                onCancelWithdrawal = onCancelSpeakerWithdrawal,
            )
        }
    }
}
