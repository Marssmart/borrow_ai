package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_filters_clear
import borrowai.composeapp.generated.resources.memory_no_match_categories
import borrowai.composeapp.generated.resources.memory_no_match_hint
import borrowai.composeapp.generated.resources.memory_no_match_title
import borrowai.composeapp.generated.resources.memory_search_clear
import org.jetbrains.compose.resources.stringResource

/** The no-results message, its hint, and the clear actions that apply (AC-3.1, AC-3.2). */
@Composable
internal fun MemoryNoResultsContent(
    noResults: MemoryNoResults,
    hasCategoryFilter: Boolean,
    onClearSearch: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = when (noResults) {
                is MemoryNoResults.Query -> stringResource(Res.string.memory_no_match_title, noResults.query)
                MemoryNoResults.Categories -> stringResource(Res.string.memory_no_match_categories)
            },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(Res.string.memory_no_match_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (noResults is MemoryNoResults.Query) {
                FilledTonalButton(onClick = onClearSearch) {
                    Text(stringResource(Res.string.memory_search_clear))
                }
            }
            if (hasCategoryFilter) {
                FilledTonalButton(onClick = onClearFilters) {
                    Text(stringResource(Res.string.memory_filters_clear))
                }
            }
        }
    }
}
