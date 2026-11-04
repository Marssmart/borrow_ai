package org.deer.borrowai

import androidx.compose.ui.input.key.Key
import org.deer.borrowai.data.domain.Memory
import org.deer.borrowai.data.domain.MemorySearchResults
import org.deer.borrowai.data.domain.MemorySearcher
import org.deer.borrowai.data.domain.TranscriptionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemorySearchRulesTest {
    @Test
    fun aQueryWithNoMatchesNamesTheQuery() {
        val library = listOf(memory("a", 1, "budget"))

        val results = MemorySearcher().search(library, "dentist")

        assertEquals(MemoryNoResults.Query("dentist"), memoryNoResults(results))
    }

    @Test
    fun chipsWithNoMatchesUseTheCategoryMessage() {
        val results = MemorySearchResults(categories = setOf(1), total = 3)

        assertEquals(MemoryNoResults.Categories, memoryNoResults(results))
    }

    @Test
    fun aQueryAndChipsWithNoMatchesNameTheQuery() {
        val results = MemorySearchResults(query = "dentist", categories = setOf(1), total = 3)

        assertEquals(MemoryNoResults.Query("dentist"), memoryNoResults(results))
    }

    @Test
    fun anEmptyLibraryIsNotANoResultsState() {
        val results = MemorySearchResults(query = "dentist", total = 0)

        assertNull(memoryNoResults(results))
    }

    @Test
    fun aListWithMatchesHasNoNoResultsState() {
        val results = MemorySearcher().search(listOf(memory("a", 1, "budget")), "budget")

        assertNull(memoryNoResults(results))
    }

    @Test
    fun anUnfilteredListHasNoNoResultsState() {
        assertNull(memoryNoResults(MemorySearchResults(total = 3)))
    }

    @Test
    fun systemBackHidesTheKeyboardBeforeClearingTheQuery() {
        assertEquals(
            MemoryListBackAction.HIDE_KEYBOARD,
            memoryListBackAction(keyboardVisible = true, searchText = "budget"),
        )
        assertEquals(
            MemoryListBackAction.HIDE_KEYBOARD,
            memoryListBackAction(keyboardVisible = true, searchText = ""),
        )
    }

    @Test
    fun systemBackClearsAQueryOnceTheKeyboardIsHidden() {
        assertEquals(
            MemoryListBackAction.CLEAR_QUERY,
            memoryListBackAction(keyboardVisible = false, searchText = "budget"),
        )
    }

    @Test
    fun systemBackIsNotTakenWithNoKeyboardAndNoQuery() {
        assertNull(memoryListBackAction(keyboardVisible = false, searchText = ""))
    }

    @Test
    fun enterRunsTheSearchFromAHardwareKeyboard() {
        assertEquals(MemorySearchKeyAction.RUN_SEARCH, memorySearchKeyAction(Key.Enter, searchText = "budget"))
        assertEquals(MemorySearchKeyAction.RUN_SEARCH, memorySearchKeyAction(Key.NumPadEnter, searchText = "budget"))
    }

    @Test
    fun escapeClearsANonEmptyQuery() {
        assertEquals(MemorySearchKeyAction.CLEAR_QUERY, memorySearchKeyAction(Key.Escape, searchText = "budget"))
    }

    @Test
    fun escapeWithNoQueryIsNotTaken() {
        assertNull(memorySearchKeyAction(Key.Escape, searchText = ""))
    }

    @Test
    fun otherKeysAreNotTakenBySearch() {
        assertNull(memorySearchKeyAction(Key.A, searchText = "budget"))
    }

    private fun memory(id: String, createdAt: Long, transcription: String): Memory = Memory(
        id = id,
        createdAtEpochMillis = createdAt,
        title = "",
        summary = "",
        transcription = transcription,
        transcriptionStatus = TranscriptionStatus.DONE,
        categories = emptyList(),
    )
}
