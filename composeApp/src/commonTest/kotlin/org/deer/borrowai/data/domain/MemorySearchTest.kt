package org.deer.borrowai.data.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemorySearchTest {
    @Test
    fun normalizeQueryTrimsAndCollapsesWhitespace() {
        assertEquals("budget plan", MemorySearch.normalizeQuery("  budget \t  plan\n "))
    }

    @Test
    fun normalizeQueryOfBlankTextIsEmpty() {
        assertEquals("", MemorySearch.normalizeQuery("   "))
    }

    @Test
    fun queriesLongerThan100CharactersAreCutAndFlagged() {
        val raw = "a".repeat(150)

        assertEquals(100, MemorySearch.normalizeQuery(raw).length)
        assertEquals(100, MemorySearch.limitInput(raw).length)
        assertTrue(MemorySearch.isInputCut(raw))
        assertFalse(MemorySearch.isInputCut("a".repeat(100)))
    }

    @Test
    fun matchingIgnoresCase() {
        val memories = listOf(memory("a", 1, title = "Dentist Visit"))

        assertEquals(listOf("a"), search(memories, "DENTIST"))
    }

    @Test
    fun matchingUsesSubstringsSoPartialWordsMatch() {
        val memories = listOf(memory("a", 1, transcription = "We had a meeting about budgets"))

        assertEquals(listOf("a"), search(memories, "meet"))
        assertEquals(listOf("a"), search(memories, "budget"))
    }

    @Test
    fun everyTermMustMatch() {
        val memories = listOf(memory("a", 1, title = "Budget review"))

        assertEquals(listOf("a"), search(memories, "budget review"))
        assertEquals(emptyList(), search(memories, "budget dentist"))
    }

    @Test
    fun differentTermsMayMatchDifferentFields() {
        val memories = listOf(
            memory("a", 1, title = "Dentist", transcription = "Bring the insurance card"),
        )

        assertEquals(listOf("a"), search(memories, "dentist insurance"))
    }

    @Test
    fun aTermCannotSpanTwoFields() {
        val memories = listOf(memory("a", 1, title = "meet", transcription = "ing notes"))

        assertEquals(emptyList(), search(memories, "meeting"))
    }

    @Test
    fun blankQueryShowsEveryMemoryNewestFirst() {
        val memories = listOf(
            memory("oldest", 1, transcription = "first"),
            memory("newest", 3, transcription = "third"),
            memory("middle", 2, transcription = "second"),
        )

        assertEquals(listOf("newest", "middle", "oldest"), search(memories, "   "))
    }

    @Test
    fun matchesAreReturnedNewestFirst() {
        val memories = listOf(
            memory("old", 1, transcription = "budget"),
            memory("new", 5, transcription = "budget"),
        )

        assertEquals(listOf("new", "old"), search(memories, "budget"))
    }

    @Test
    fun memoryWithNoTextYetNeverMatches() {
        val memories = listOf(
            memory("pending", 2),
            memory("done", 1, transcription = "budget"),
        )

        assertEquals(listOf("done"), search(memories, "budget"))
        assertEquals(emptyList(), search(memories, "a"))
    }

    @Test
    fun summaryIsSearched() {
        val memories = listOf(memory("a", 1, summary = "Agreed the launch date"))

        assertEquals(listOf("a"), search(memories, "launch"))
    }

    @Test
    fun queryIsCutBeforeMatching() {
        val memories = listOf(
            memory("exact", 2, transcription = "x".repeat(100)),
            memory("short", 1, transcription = "x".repeat(99)),
        )

        assertEquals(listOf("exact"), search(memories, "x".repeat(150)))
    }

    @Test
    fun resultsReportTheNormalizedQuery() {
        val results = MemorySearcher().search(listOf(memory("a", 1, title = "Dentist")), "  dentist   ")

        assertEquals("dentist", results.query)
        assertTrue(results.isFiltered)
    }

    @Test
    fun cachedTextFollowsChangesToAMemory() {
        val searcher = MemorySearcher()
        val before = listOf(memory("a", 1, transcription = "budget"))
        assertEquals(listOf("a"), searcher.search(before, "budget").memories.map { it.id })

        val after = listOf(memory("a", 1, transcription = "dentist"))

        assertEquals(emptyList(), searcher.search(after, "budget").memories.map { it.id })
        assertEquals(listOf("a"), searcher.search(after, "dentist").memories.map { it.id })
    }

    @Test
    fun categoriesCombineAsOrAndTheQueryAsAnd() {
        val memories = listOf(
            memory("travel", 3, transcription = "budget for the trip", categories = listOf(1)),
            memory("family", 2, transcription = "budget for family", categories = listOf(2)),
            memory("work", 1, transcription = "budget review", categories = listOf(3)),
        )
        val searcher = MemorySearcher()

        assertEquals(listOf("travel", "family"), searcher.search(memories, "budget", setOf(1, 2)).memories.map { it.id })
        assertEquals(listOf("family"), searcher.search(memories, "family", setOf(1, 2)).memories.map { it.id })
        assertEquals(listOf("travel", "family", "work"), searcher.search(memories, "", emptySet()).memories.map { it.id })
        assertTrue(searcher.search(memories, "", setOf(1)).isFiltered)
    }

    private fun search(memories: List<Memory>, query: String): List<String> =
        MemorySearcher().search(memories, query).memories.map { it.id }

    private fun memory(
        id: String,
        createdAt: Long,
        title: String = "",
        summary: String = "",
        transcription: String = "",
        categories: List<Int> = emptyList(),
    ): Memory = Memory(
        id = id,
        createdAtEpochMillis = createdAt,
        title = title,
        summary = summary,
        transcription = transcription,
        transcriptionStatus = if (transcription.isBlank()) TranscriptionStatus.PENDING else TranscriptionStatus.DONE,
        categories = categories,
    )
}
