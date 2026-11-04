package org.deer.borrowai.data.domain

/**
 * Rules for matching saved memories against a search query. Pure Kotlin, so the same rules run on every platform.
 *
 * A query is trimmed, its whitespace is collapsed, and it is cut to [MAX_QUERY_LENGTH] characters. Matching ignores
 * case. Every term must appear as a substring of a memory's title, summary, or transcript, and different terms may
 * match different fields. A memory with no text yet, such as one whose transcript is still pending, has nothing to
 * match, so it never matches a non-empty query.
 */
object MemorySearch {
    const val MAX_QUERY_LENGTH = 100

    /** The query as the matcher reads it: trimmed, whitespace collapsed, and cut to [MAX_QUERY_LENGTH]. */
    fun normalizeQuery(raw: String): String =
        raw.trim().split(WHITESPACE_RUN).joinToString(" ").take(MAX_QUERY_LENGTH).trimEnd()

    /** Cuts text typed into the search field to [MAX_QUERY_LENGTH] characters. */
    fun limitInput(raw: String): String = raw.take(MAX_QUERY_LENGTH)

    /** True when [raw] is longer than [MAX_QUERY_LENGTH], so the search field has to cut it. */
    fun isInputCut(raw: String): Boolean = raw.length > MAX_QUERY_LENGTH

    private val WHITESPACE_RUN = Regex("\\s+")
}

/** The memories that match a query, newest first, with the normalized query they were matched with. */
data class MemorySearchResults(
    val memories: List<Memory> = emptyList(),
    val query: String = "",
    val categories: Set<Int> = emptySet(),
) {
    val isFiltered: Boolean
        get() = query.isNotEmpty() || categories.isNotEmpty()
}

/**
 * Runs [MemorySearch] over a library of memories. It keeps a lowercase copy of each memory's text, keyed by memory id,
 * and reuses that copy until the title, summary, or transcript changes, so a keystroke does not lowercase every
 * transcript again. The cache is not thread-safe: call [search] from one coroutine at a time.
 */
class MemorySearcher {
    private val texts = HashMap<String, SearchableText>()

    fun search(memories: List<Memory>, query: String, categories: Set<Int> = emptySet()): MemorySearchResults {
        val normalized = MemorySearch.normalizeQuery(query)
        val terms = normalized.lowercase().split(' ').filter(String::isNotEmpty)
        forgetRemovedMemories(memories)
        val matches = memories.filter { memory ->
            memory.hasAnyCategory(categories) && (terms.isEmpty() || textOf(memory).containsAll(terms))
        }
        return MemorySearchResults(
            memories = matches.sortedByDescending(Memory::createdAtEpochMillis),
            query = normalized,
            categories = categories,
        )
    }

    private fun textOf(memory: Memory): SearchableText {
        val cached = texts[memory.id]
        if (cached != null && cached.isCopyOf(memory)) return cached
        return SearchableText(memory).also { texts[memory.id] = it }
    }

    private fun forgetRemovedMemories(memories: List<Memory>) {
        if (texts.isEmpty()) return
        texts.keys.retainAll(memories.mapTo(HashSet(memories.size)) { it.id })
    }
}

private class SearchableText(memory: Memory) {
    private val sourceTitle = memory.title
    private val sourceSummary = memory.summary
    private val sourceTranscription = memory.transcription
    private val lowercaseFields = listOf(sourceTitle, sourceSummary, sourceTranscription).map(String::lowercase)

    fun isCopyOf(memory: Memory): Boolean =
        memory.title == sourceTitle &&
            memory.summary == sourceSummary &&
            memory.transcription == sourceTranscription

    fun containsAll(terms: List<String>): Boolean =
        terms.all { term -> lowercaseFields.any { field -> field.contains(term) } }
}
