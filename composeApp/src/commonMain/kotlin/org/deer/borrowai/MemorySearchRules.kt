package org.deer.borrowai

import androidx.compose.ui.input.key.Key
import org.deer.borrowai.data.domain.MemorySearchResults

/** Why the Memories list shows a no-results message in place of memories. */
internal sealed interface MemoryNoResults {
    /** The query matched nothing, so the message names it. */
    data class Query(val query: String) : MemoryNoResults

    /** Only category chips are selected, and no memory has one of them. */
    data object Categories : MemoryNoResults
}

/**
 * The no-results state (AC-3.1). It is null when the list has memories, when nothing is filtered, and when the library
 * has no memories at all. An empty library shows the empty state from the delete design instead (AC-3.3).
 */
internal fun memoryNoResults(results: MemorySearchResults): MemoryNoResults? = when {
    results.total == 0 || results.memories.isNotEmpty() || !results.isFiltered -> null
    results.query.isNotEmpty() -> MemoryNoResults.Query(results.query)
    else -> MemoryNoResults.Categories
}

/** The steps system back takes on the Memories tab, in order (AC-4.5). */
internal enum class MemoryListBackAction { HIDE_KEYBOARD, CLEAR_QUERY }

/** Null when back is not for the list, so it does what it does today. */
internal fun memoryListBackAction(keyboardVisible: Boolean, searchText: String): MemoryListBackAction? = when {
    keyboardVisible -> MemoryListBackAction.HIDE_KEYBOARD
    searchText.isNotEmpty() -> MemoryListBackAction.CLEAR_QUERY
    else -> null
}

/** What a hardware key does in the search field (AC-4.4). */
internal enum class MemorySearchKeyAction { RUN_SEARCH, CLEAR_QUERY }

/** Enter runs the search. Escape clears the text, and is not taken when there is no text. */
internal fun memorySearchKeyAction(key: Key, searchText: String): MemorySearchKeyAction? = when (key) {
    Key.Enter, Key.NumPadEnter -> MemorySearchKeyAction.RUN_SEARCH
    Key.Escape -> MemorySearchKeyAction.CLEAR_QUERY.takeIf { searchText.isNotEmpty() }
    else -> null
}
