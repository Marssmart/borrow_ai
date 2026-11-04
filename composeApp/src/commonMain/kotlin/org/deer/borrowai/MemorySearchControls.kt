package org.deer.borrowai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.memory_count_all
import borrowai.composeapp.generated.resources.memory_count_filtered
import borrowai.composeapp.generated.resources.memory_search_clear
import borrowai.composeapp.generated.resources.memory_search_counter
import borrowai.composeapp.generated.resources.memory_search_label
import borrowai.composeapp.generated.resources.memory_search_limit_reached
import org.deer.borrowai.data.domain.MemorySearch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal val MemorySearchControlsPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 10.dp)

/** The counter appears once the query reaches this many characters, so it is not shown on every keystroke. */
private const val MEMORY_SEARCH_COUNTER_THRESHOLD = 80
private val MEMORY_SEARCH_GLYPH_SIZE = 18.dp

/** The saved memories search field. The query itself is applied by [SavedMemoriesStateHolder]. */
@Composable
internal fun MemorySearchField(
    text: String,
    inputCut: Boolean,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val clearLabel = stringResource(Res.string.memory_search_clear)
    val runSearch = {
        keyboardController?.hide()
        onSearch()
    }
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent { event ->
            val action = memorySearchKeyAction(event.key, text)
            when {
                action == null || event.type != KeyEventType.KeyDown -> false
                action == MemorySearchKeyAction.RUN_SEARCH -> {
                    runSearch()
                    true
                }

                else -> {
                    onClear()
                    true
                }
            }
        },
        label = { Text(stringResource(Res.string.memory_search_label)) },
        singleLine = true,
        leadingIcon = { SearchGlyph() },
        trailingIcon = if (text.isEmpty()) {
            null
        } else {
            {
                IconButton(
                    onClick = {
                        onClear()
                        focusRequester.requestFocus()
                    },
                    modifier = Modifier.semantics { contentDescription = clearLabel },
                ) {
                    CloseGlyph()
                }
            }
        },
        supportingText = when {
            inputCut -> {
                {
                    Text(
                        stringResource(
                            Res.string.memory_search_limit_reached,
                            MemorySearch.MAX_QUERY_LENGTH,
                        ),
                    )
                }
            }

            text.length >= MEMORY_SEARCH_COUNTER_THRESHOLD -> {
                {
                    Text(
                        stringResource(
                            Res.string.memory_search_counter,
                            text.length,
                            MemorySearch.MAX_QUERY_LENGTH,
                        ),
                    )
                }
            }

            else -> null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
    )
}

/** "42 memories", or "Showing 3 of 42" while a search is active. Announced politely when it changes. */
@Composable
internal fun MemoryCountLine(
    shown: Int,
    total: Int,
    isFiltered: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (isFiltered) {
            stringResource(Res.string.memory_count_filtered, shown, total)
        } else {
            pluralStringResource(Res.plurals.memory_count_all, total, total)
        },
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun SearchGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(MEMORY_SEARCH_GLYPH_SIZE).clearAndSetSemantics { }) {
        val strokeWidth = 2.dp.toPx()
        val radius = size.minDimension * 0.3f
        val center = Offset(size.width * 0.44f, size.height * 0.44f)
        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth))
        drawLine(
            color = color,
            start = Offset(center.x + radius * 0.7f, center.y + radius * 0.7f),
            end = Offset(size.width * 0.9f, size.height * 0.9f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun CloseGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(MEMORY_SEARCH_GLYPH_SIZE).clearAndSetSemantics { }) {
        val strokeWidth = 2.dp.toPx()
        val inset = size.minDimension * 0.25f
        drawLine(
            color = color,
            start = Offset(inset, inset),
            end = Offset(size.width - inset, size.height - inset),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width - inset, inset),
            end = Offset(inset, size.height - inset),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
