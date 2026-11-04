package org.deer.borrowai.data.domain

/** One category filter chip: the category [id], its [name], and how many visible memories use it. */
data class CategoryFilterChip(
    val id: Int,
    val name: String,
    val count: Int,
)

/** How many of [memories] use each category id. Ids that no memory uses are absent. */
fun categoriesInUse(memories: List<Memory>): Map<Int, Int> {
    val counts = HashMap<Int, Int>()
    memories.forEach { memory ->
        memory.categories.forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
    }
    return counts
}

/**
 * The selected categories that some memory still uses. A selected category that is no longer in use filters nothing,
 * so the list never hides memories behind a chip that is no longer shown.
 */
fun effectiveCategoryFilter(selected: Set<Int>, inUse: Set<Int>): Set<Int> = selected intersect inUse

/** True when no category is selected, or when this memory has at least one of the selected categories (OR). */
fun Memory.hasAnyCategory(selected: Set<Int>): Boolean =
    selected.isEmpty() || categories.any { it in selected }
