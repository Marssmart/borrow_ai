package org.deer.borrowai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_category_chip
import borrowai.composeapp.generated.resources.memory_filters_clear
import borrowai.composeapp.generated.resources.memory_filters_clear_description
import org.deer.borrowai.data.domain.CategoryFilterChip
import org.jetbrains.compose.resources.stringResource

private val MEMORY_CATEGORY_CHIP_SPACING = 8.dp

/** One chip per category in use, and Clear filters while a chip is selected. Several selected chips combine with OR. */
@Composable
internal fun CategoryFilterRow(
    chips: List<CategoryFilterChip>,
    selectedIds: Set<Int>,
    onToggle: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clearDescription = stringResource(Res.string.memory_filters_clear_description)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(MEMORY_CATEGORY_CHIP_SPACING),
        ) {
            items(chips, key = CategoryFilterChip::id) { chip ->
                CategoryFilterChipButton(
                    chip = chip,
                    selected = chip.id in selectedIds,
                    onClick = { onToggle(chip.id) },
                )
            }
        }
        if (selectedIds.isNotEmpty()) {
            TextButton(
                onClick = onClear,
                modifier = Modifier.semantics { contentDescription = clearDescription },
            ) {
                Text(stringResource(Res.string.memory_filters_clear))
            }
        }
    }
}

@Composable
private fun CategoryFilterChipButton(
    chip: CategoryFilterChip,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // The check mark is a second cue besides the colour, so the selected state is not shown by colour alone.
    val checkMark: @Composable (() -> Unit)? = if (selected) {
        { Text("✓", modifier = Modifier.clearAndSetSemantics { }) }
    } else {
        null
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(Res.string.memory_category_chip, chip.name, chip.count)) },
        leadingIcon = checkMark,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.minimumInteractiveComponentSize(),
    )
}
