package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_banner_dismiss
import borrowai.composeapp.generated.resources.memory_banner_keep_mine
import borrowai.composeapp.generated.resources.memory_banner_replace
import borrowai.composeapp.generated.resources.memory_banner_replace_categories_message
import borrowai.composeapp.generated.resources.memory_banner_replace_categories_title
import borrowai.composeapp.generated.resources.memory_banner_replace_summary_message
import borrowai.composeapp.generated.resources.memory_banner_replace_summary_title
import borrowai.composeapp.generated.resources.memory_banner_suggest_categories
import borrowai.composeapp.generated.resources.memory_banner_transcript_changed
import borrowai.composeapp.generated.resources.memory_banner_write_summary
import org.jetbrains.compose.resources.stringResource

private enum class BannerReplacement { SUMMARY, CATEGORIES }

/**
 * Shown after a saved transcript change. The summary and categories were made from the earlier transcript, so the user
 * can ask for new ones. Each action asks first when it would replace text or choices the user made.
 */
@Composable
internal fun MemoryTranscriptBanner(
    summaryWrittenByUser: Boolean,
    categoriesChosenByUser: Boolean,
    onWriteSummary: () -> Unit,
    onSuggestCategories: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<BannerReplacement?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.memory_banner_transcript_changed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        if (summaryWrittenByUser) confirming = BannerReplacement.SUMMARY else onWriteSummary()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(Res.string.memory_banner_write_summary))
                }
                FilledTonalButton(
                    onClick = {
                        if (categoriesChosenByUser) confirming = BannerReplacement.CATEGORIES else onSuggestCategories()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(Res.string.memory_banner_suggest_categories))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.memory_banner_dismiss))
                }
            }
        }
    }

    when (confirming) {
        BannerReplacement.SUMMARY -> AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(Res.string.memory_banner_replace_summary_title)) },
            text = { Text(stringResource(Res.string.memory_banner_replace_summary_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onWriteSummary()
                }) { Text(stringResource(Res.string.memory_banner_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.memory_banner_keep_mine)) }
            },
        )
        BannerReplacement.CATEGORIES -> AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(Res.string.memory_banner_replace_categories_title)) },
            text = { Text(stringResource(Res.string.memory_banner_replace_categories_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onSuggestCategories()
                }) { Text(stringResource(Res.string.memory_banner_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.memory_banner_keep_mine)) }
            },
        )
        null -> Unit
    }
}
