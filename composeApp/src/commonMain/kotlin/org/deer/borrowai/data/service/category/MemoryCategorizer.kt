package org.deer.borrowai.data.service.category

import org.deer.borrowai.data.domain.MAX_MEMORY_CATEGORIES
import org.deer.borrowai.data.domain.categoryIdsViolation

/** Chooses the master categories that describe a memory's transcription. */
interface MemoryCategorizer {
    suspend fun categorize(transcription: String): CategorizationResult
}

sealed interface CategorizationResult {
    /** One to [MAX_MEMORY_CATEGORIES] distinct master category ids. */
    data class Categories(val ids: List<Int>) : CategorizationResult {
        init {
            require(ids.isNotEmpty()) { "A categorization must assign at least one category." }
            categoryIdsViolation(ids)?.let { throw IllegalArgumentException(it) }
        }
    }

    /** The transcription was blank, so there was nothing to categorize. */
    data object Empty : CategorizationResult

    /** No categorizer is available on this build. */
    data object Unavailable : CategorizationResult

    /** The categorizer model file is not on the device. */
    data object ModelMissing : CategorizationResult

    /** The categorizer failed. The memory keeps no categories and can be retried later. */
    data class Failed(val cause: Throwable) : CategorizationResult
}

/** Sends one system instruction and user message to a language model. */
fun interface CategoryModel {
    suspend fun generate(systemInstruction: String, userMessage: String): CategoryModelReply
}

sealed interface CategoryModelReply {
    data class Text(val text: String) : CategoryModelReply

    data object ModelMissing : CategoryModelReply

    data class Failed(val cause: Throwable) : CategoryModelReply
}

/** Categorizer used until a local model runtime is wired in. */
object UnavailableMemoryCategorizer : MemoryCategorizer {
    override suspend fun categorize(transcription: String): CategorizationResult =
        CategorizationResult.Unavailable
}
