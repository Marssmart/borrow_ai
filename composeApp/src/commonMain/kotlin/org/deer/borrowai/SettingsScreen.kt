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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.settings_background_indicator_description
import borrowai.composeapp.generated.resources.settings_background_indicator_title
import borrowai.composeapp.generated.resources.settings_gpu_acceleration_description
import borrowai.composeapp.generated.resources.settings_gpu_acceleration_title
import borrowai.composeapp.generated.resources.settings_retry_missing_button
import borrowai.composeapp.generated.resources.settings_retry_missing_description
import borrowai.composeapp.generated.resources.settings_retry_missing_done
import borrowai.composeapp.generated.resources.settings_retry_missing_failed
import borrowai.composeapp.generated.resources.settings_retry_missing_none
import borrowai.composeapp.generated.resources.settings_retry_missing_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsScreen(
    showBackgroundActivityIndicator: Boolean,
    onShowBackgroundActivityIndicatorChange: (Boolean) -> Unit,
    useGpuAcceleration: Boolean,
    onUseGpuAccelerationChange: (Boolean) -> Unit,
    onRetryMissingData: suspend () -> Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var retryState by remember { mutableStateOf<RetryState>(RetryState.Idle) }
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_retry_missing_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.settings_retry_missing_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = retryState != RetryState.Running,
                onClick = {
                    retryState = RetryState.Running
                    scope.launch {
                        retryState = try {
                            RetryState.Done(onRetryMissingData())
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            RetryState.Failed
                        }
                    }
                },
            ) {
                Text(stringResource(Res.string.settings_retry_missing_button))
            }
            when (val state = retryState) {
                is RetryState.Done -> Text(
                    text = if (state.count == 0) {
                        stringResource(Res.string.settings_retry_missing_none)
                    } else {
                        pluralStringResource(Res.plurals.settings_retry_missing_done, state.count, state.count)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RetryState.Failed -> Text(
                    text = stringResource(Res.string.settings_retry_missing_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }
}

private sealed interface RetryState {
    data object Idle : RetryState

    data object Running : RetryState

    data class Done(val count: Int) : RetryState

    data object Failed : RetryState
}
