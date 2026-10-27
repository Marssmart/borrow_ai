package org.deer.borrowai.data.service.category

import arrow.core.Either
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.deer.borrowai.data.domain.Category
import org.deer.borrowai.data.domain.MAX_MEMORY_CATEGORIES
import org.deer.borrowai.data.service.MemoryError
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.category.CategoryPrompt.BACKFILL_SYSTEM_INSTRUCTION
import org.deer.borrowai.data.service.category.CategoryPrompt.MATCH_THRESHOLD
import org.deer.borrowai.data.service.category.CategoryPrompt.SCORING_SYSTEM_INSTRUCTION

/**
 * Scores master categories in batches with [model], keeps those at or above [MATCH_THRESHOLD], and asks the
 * model to backfill new categories when fewer than [MAX_MEMORY_CATEGORIES] match. A memory always ends with at
 * least one category unless the model fails, in which case the result is [CategorizationResult.Failed] and the
 * memory can be retried.
 */
class DefaultMemoryCategorizer(
    private val memoryService: MemoryService,
    private val model: CategoryModel,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
) : MemoryCategorizer {
    private val backfillLock = Mutex()

    init {
        require(batchSize > 0) { "Batch size must be positive." }
    }

    override suspend fun categorize(transcription: String): CategorizationResult {
        if (transcription.isBlank()) return CategorizationResult.Empty

        val masters = memoryService.findCategories().fold(
            ifLeft = { return failure(it) },
            ifRight = { it },
        )

        val scores = when (val scoring = scoreAll(transcription, masters)) {
            is ScoringOutcome.Scored -> scoring.scores
            is ScoringOutcome.Stopped -> return scoring.result
        }

        val chosen = scores.entries
            .filter { it.value >= MATCH_THRESHOLD }
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(MAX_MEMORY_CATEGORIES)
            .map { it.key }
            .toMutableList()

        var backfillFailure: Throwable? = null
        if (chosen.size < MAX_MEMORY_CATEGORIES) {
            when (val backfill = backfill(transcription, masters, MAX_MEMORY_CATEGORIES - chosen.size)) {
                is BackfillOutcome.Created -> {
                    chosen += backfill.ids
                    backfillFailure = backfill.failure
                }
                is BackfillOutcome.Stopped -> {
                    if (chosen.isEmpty()) return backfill.result
                    backfillFailure = backfill.result.asCause()
                }
            }
        }

        if (chosen.isEmpty()) {
            val best = scores.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()
                ?.key
                ?: return CategorizationResult.Failed(
                    backfillFailure ?: IllegalStateException("No category could be assigned."),
                )
            chosen += best
        }

        return CategorizationResult.Categories(chosen, backfillFailure)
    }

    /** Bounds one model call, so the time allowed grows with the number of calls, not with a whole run. */
    private suspend fun generate(systemInstruction: String, userMessage: String): CategoryModelReply = try {
        withTimeout(callTimeoutMillis) { model.generate(systemInstruction, userMessage) }
    } catch (exception: TimeoutCancellationException) {
        CategoryModelReply.Failed(IllegalStateException("Model call timed out after $callTimeoutMillis ms."))
    }

    private suspend fun scoreAll(transcription: String, masters: List<Category>): ScoringOutcome {
        val scores = linkedMapOf<Int, Int>()
        for (batch in masters.chunked(batchSize)) {
            val batchIds = batch.mapTo(mutableSetOf()) { it.id }
            var batchScores: Map<Int, Int> = emptyMap()
            for (attempt in 1..MAX_SCORING_ATTEMPTS) {
                val reply = generate(SCORING_SYSTEM_INSTRUCTION, CategoryPrompt.scoringMessage(transcription, batch))
                when (reply) {
                    is CategoryModelReply.Text -> {
                        batchScores = CategoryPrompt.parseScores(reply.text, batchIds)
                        if (batchScores.keys == batchIds) break
                        logCategorization(
                            "Scoring attempt $attempt covered ${batchScores.size} of ${batchIds.size} categories " +
                                "(reply ${reply.text.length} chars).",
                        )
                    }
                    CategoryModelReply.ModelMissing -> return ScoringOutcome.Stopped(CategorizationResult.ModelMissing)
                    is CategoryModelReply.Failed -> return ScoringOutcome.Stopped(CategorizationResult.Failed(reply.cause))
                }
            }
            if (batchScores.keys != batchIds) {
                return ScoringOutcome.Stopped(
                    CategorizationResult.Failed(
                        IllegalStateException("Scoring reply covered ${batchScores.size} of ${batchIds.size} categories."),
                    ),
                )
            }
            scores.putAll(batchScores)
        }
        return ScoringOutcome.Scored(scores)
    }

    private suspend fun backfill(transcription: String, masters: List<Category>, count: Int): BackfillOutcome {
        val reply = generate(BACKFILL_SYSTEM_INSTRUCTION, CategoryPrompt.backfillMessage(transcription, masters, count))
        val text = when (reply) {
            is CategoryModelReply.Text -> reply.text
            CategoryModelReply.ModelMissing -> return BackfillOutcome.Stopped(CategorizationResult.ModelMissing)
            is CategoryModelReply.Failed -> return BackfillOutcome.Stopped(CategorizationResult.Failed(reply.cause))
        }

        return backfillLock.withLock {
            val current = memoryService.findCategories().fold(
                ifLeft = { return@withLock BackfillOutcome.Stopped(failure(it)) },
                ifRight = { it },
            )
            val masterNames = masters.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
            val concurrentByName = current
                .filter { it.name.trim().lowercase() !in masterNames }
                .associateBy { it.name.trim().lowercase() }
            val ids = mutableListOf<Int>()
            var persistenceFailure: Throwable? = null
            for (proposal in CategoryPrompt.parseProposals(text, masterNames)) {
                if (ids.size >= count) break
                val concurrent = concurrentByName[proposal.name.trim().lowercase()]
                if (concurrent != null) {
                    ids += concurrent.id
                    continue
                }
                when (val created = memoryService.createCategory(proposal.name, proposal.description)) {
                    is Either.Left -> {
                        persistenceFailure = persistenceFailure
                            ?: IllegalStateException("Category create failed: ${created.value}")
                    }
                    is Either.Right -> ids += created.value.id
                }
            }
            if (ids.isEmpty() && persistenceFailure == null) {
                logCategorization("Backfill reply yielded no usable categories (reply ${text.length} chars).")
            }
            BackfillOutcome.Created(ids, persistenceFailure)
        }
    }

    private fun failure(error: MemoryError): CategorizationResult.Failed =
        CategorizationResult.Failed(IllegalStateException("Category lookup failed: $error"))

    private fun CategorizationResult.asCause(): Throwable = when (this) {
        is CategorizationResult.Failed -> cause
        CategorizationResult.ModelMissing -> IllegalStateException("Categorizer model is missing.")
        else -> IllegalStateException("Backfill did not complete: $this")
    }

    private sealed interface ScoringOutcome {
        data class Scored(val scores: Map<Int, Int>) : ScoringOutcome
        data class Stopped(val result: CategorizationResult) : ScoringOutcome
    }

    private sealed interface BackfillOutcome {
        /** [failure] is the first persistence error, if any proposed category could not be created. */
        data class Created(val ids: List<Int>, val failure: Throwable?) : BackfillOutcome
        data class Stopped(val result: CategorizationResult) : BackfillOutcome
    }

    companion object {
        /**
         * Scored one category per request. On device the model replied to a 10-category batch with a single
         * `id|score` line, so every batch failed coverage.
         */
        const val DEFAULT_BATCH_SIZE = 1

        /** Generous for one on-device call, which took about 4 s in testing. */
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 60 * 1000L

        /** A batch whose reply does not score every category is asked again once before failing. */
        const val MAX_SCORING_ATTEMPTS = 2
    }
}
