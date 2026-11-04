package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_delete
import borrowai.composeapp.generated.resources.memory_delete_failed
import borrowai.composeapp.generated.resources.memory_deleted
import borrowai.composeapp.generated.resources.memory_empty_body
import borrowai.composeapp.generated.resources.memory_empty_go_to_record
import borrowai.composeapp.generated.resources.memory_empty_title
import borrowai.composeapp.generated.resources.memory_more_actions
import borrowai.composeapp.generated.resources.memory_undo
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.first

/** The More actions button in the detail header. Its menu holds the delete action, in the error colour with its label. */
@Composable
internal fun MemoryMoreActions(onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.memory_more_actions)
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(MEMORY_MORE_ACTIONS_SIZE).semantics { contentDescription = label },
        ) {
            Text(
                "⋮",
                modifier = Modifier.clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(stringResource(Res.string.memory_delete), color = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * Shows the Undo snackbar while a delete is in its window, and an error when a delete could not be written. A new
 * delete replaces an error still on screen, so its Undo is never queued behind that error.
 */
@Composable
internal fun MemoryDeleteSnackbarHost(
    hostState: SnackbarHostState,
    undoWindow: UndoWindow?,
    deleteFailures: Int,
    onUndo: (memoryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnUndo by rememberUpdatedState(onUndo)
    val currentUndoWindow by rememberUpdatedState(undoWindow)
    val deletedMessage = stringResource(Res.string.memory_deleted)
    val undoLabel = stringResource(Res.string.memory_undo)
    val failedMessage = stringResource(Res.string.memory_delete_failed)
    // Keyed on the window, so each delete restarts the snackbar. The state holder's timer ends it by clearing the window.
    LaunchedEffect(undoWindow?.requestId) {
        val window = undoWindow ?: return@LaunchedEffect
        hostState.currentSnackbarData?.dismiss()
        val result = hostState.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) currentOnUndo(window.memoryId)
    }
    LaunchedEffect(deleteFailures) {
        if (deleteFailures == 0) return@LaunchedEffect
        // An error waits until no Undo window is open, so it never queues ahead of the next delete's Undo.
        snapshotFlow { currentUndoWindow == null }.first { it }
        hostState.showSnackbar(message = failedMessage, duration = SnackbarDuration.Long)
    }
    SnackbarHost(hostState = hostState, modifier = modifier)
}

/** The empty library: a heading, one line of text, and a way to the Record tab. It shows in place of the cards. */
@Composable
internal fun MemoriesEmptyState(onGoToRecord: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.memory_empty_title),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(Res.string.memory_empty_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onGoToRecord) {
            Text(stringResource(Res.string.memory_empty_go_to_record))
        }
    }
}

private val MEMORY_MORE_ACTIONS_SIZE = 48.dp
