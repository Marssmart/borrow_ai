package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import borrowai.composeapp.generated.resources.settings_speaker_action_failed
import borrowai.composeapp.generated.resources.settings_speaker_consent_agree
import borrowai.composeapp.generated.resources.settings_speaker_consent_decline
import borrowai.composeapp.generated.resources.settings_speaker_consent_text
import borrowai.composeapp.generated.resources.settings_speaker_consent_title
import borrowai.composeapp.generated.resources.settings_speaker_download
import borrowai.composeapp.generated.resources.settings_speaker_download_failed
import borrowai.composeapp.generated.resources.settings_speaker_download_progress
import borrowai.composeapp.generated.resources.settings_speaker_models_waiting_for_wifi
import borrowai.composeapp.generated.resources.settings_speaker_models_available
import borrowai.composeapp.generated.resources.settings_speaker_models_not_available
import borrowai.composeapp.generated.resources.settings_speaker_models_title
import borrowai.composeapp.generated.resources.settings_speaker_models_requires_ram
import borrowai.composeapp.generated.resources.settings_speaker_recognition_description
import borrowai.composeapp.generated.resources.settings_speaker_recognition_title
import borrowai.composeapp.generated.resources.settings_speaker_withdraw
import borrowai.composeapp.generated.resources.settings_speaker_withdraw_cancel
import borrowai.composeapp.generated.resources.settings_speaker_withdraw_confirm
import borrowai.composeapp.generated.resources.settings_speaker_withdraw_text
import borrowai.composeapp.generated.resources.settings_speaker_withdraw_title
import org.deer.borrowai.data.service.model.SpeakerModelSetupPhase
import org.jetbrains.compose.resources.stringResource

/** The speaker recognition toggle and the model status row in Settings. The caller leaves them out where unsupported. */
@Composable
internal fun SpeakerSettingsRows(
    state: SpeakerSettingsUiState,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    onAgreeToConsent: () -> Unit,
    onDeclineConsent: () -> Unit,
    onDownloadModels: () -> Unit,
    onDownloadOverMobileData: () -> Unit,
    onRequestWithdrawal: () -> Unit,
    onConfirmWithdrawal: () -> Unit,
    onCancelWithdrawal: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.enabled,
                    enabled = state.switchEnabled,
                    role = Role.Switch,
                    onValueChange = { if (it) onTurnOn() else onTurnOff() },
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
                text = when (state.modelSetup.phase) {
                    SpeakerModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK ->
                        stringResource(Res.string.settings_speaker_models_waiting_for_wifi)
                    SpeakerModelSetupPhase.DOWNLOADING ->
                        stringResource(
                            Res.string.settings_speaker_download_progress,
                            (state.modelSetup.progress * 100).toInt(),
                        )
                    SpeakerModelSetupPhase.UNSUPPORTED_DEVICE ->
                        stringResource(Res.string.settings_speaker_models_not_available)
                    SpeakerModelSetupPhase.FAILED -> stringResource(Res.string.settings_speaker_download_failed)
                    else -> stringResource(
                        if (state.modelAvailable) {
                            Res.string.settings_speaker_models_available
                        } else {
                            Res.string.settings_speaker_models_not_available
                        },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.modelSetup.phase == SpeakerModelSetupPhase.UNSUPPORTED_DEVICE) {
            Text(
                text = stringResource(Res.string.settings_speaker_models_requires_ram),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.modelAvailable && state.modelSetup.phase != SpeakerModelSetupPhase.UNSUPPORTED_DEVICE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.modelSetup.phase == SpeakerModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK) {
                    TextButton(onClick = onDownloadOverMobileData) {
                        Text(stringResource(Res.string.settings_speaker_download))
                    }
                } else {
                    TextButton(
                        onClick = onDownloadModels,
                        enabled = !state.downloadInProgress,
                    ) {
                        Text(stringResource(Res.string.settings_speaker_download))
                    }
                }
            }
        }
        if (state.consentAccepted) {
            TextButton(
                onClick = onRequestWithdrawal,
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(stringResource(Res.string.settings_speaker_withdraw))
            }
        }
        if (state.actionFailed) {
            Text(
                text = stringResource(Res.string.settings_speaker_action_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (state.consentDialogVisible) {
        AlertDialog(
            onDismissRequest = onDeclineConsent,
            title = { Text(stringResource(Res.string.settings_speaker_consent_title)) },
            text = { Text(stringResource(Res.string.settings_speaker_consent_text)) },
            confirmButton = {
                Button(onClick = onAgreeToConsent) {
                    Text(stringResource(Res.string.settings_speaker_consent_agree))
                }
            },
            dismissButton = {
                TextButton(onClick = onDeclineConsent) {
                    Text(stringResource(Res.string.settings_speaker_consent_decline))
                }
            },
        )
    }

    if (state.withdrawalDialogVisible) {
        AlertDialog(
            onDismissRequest = onCancelWithdrawal,
            title = { Text(stringResource(Res.string.settings_speaker_withdraw_title)) },
            text = { Text(stringResource(Res.string.settings_speaker_withdraw_text)) },
            confirmButton = {
                Button(onClick = onConfirmWithdrawal) {
                    Text(stringResource(Res.string.settings_speaker_withdraw_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelWithdrawal) {
                    Text(stringResource(Res.string.settings_speaker_withdraw_cancel))
                }
            },
        )
    }
}
