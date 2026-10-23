package org.deer.borrowai.data.service.category

import arrow.core.Either
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        if (chosen.size < MAX_MEMORY_CATEGORIES) {
            when (val backfill = backfill(transcription, masters, MAX_MEMORY_CATEGORIES - chosen.size)) {
                is BackfillOutcome.Created -> chosen += backfill.ids
                is BackfillOutcome.Stopped ->
                    if (chosen.isEmpty()) return backfill.result
            }
        }

        if (chosen.isEmpty()) {
            val best = scores.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()
                ?.key
                ?: return CategorizationResult.Failed(IllegalStateException("No category could be assigned."))
            chosen += best
        }

        return CategorizationResult.Categories(chosen)
    }

    private suspend fun scoreAll(transcription: String, masters: List<Category>): ScoringOutcome {
        val scores = linkedMapOf<Int, Int>()
        for (batch in masters.chunked(batchSize)) {
            val batchIds = batch.mapTo(mutableSetOf()) { it.id }
            val reply = model.generate(SCORING_SYSTEM_INSTRUCTION, CategoryPrompt.scoringMessage(transcription, batch))
            when (reply) {
                is CategoryModelReply.Text -> {
                    val parsed = CategoryPrompt.parseScores(reply.text, batchIds)
                    if (parsed.isEmpty()) {
                        return ScoringOutcome.Stopped(
                            CategorizationResult.Failed(IllegalStateException("Scoring reply contained no scores.")),
                        )
                    }
                    scores.putAll(parsed)
                }
                CategoryModelReply.ModelMissing -> return ScoringOutcome.Stopped(CategorizationResult.ModelMissing)
                is CategoryModelReply.Failed -> return ScoringOutcome.Stopped(CategorizationResult.Failed(reply.cause))
            }
        }
        return ScoringOutcome.Scored(scores)
    }

    private suspend fun backfill(transcription: String, masters: List<Category>, count: Int): BackfillOutcome {
        val reply = model.generate(BACKFILL_SYSTEM_INSTRUCTION, CategoryPrompt.backfillMessage(transcription, masters, count))
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
            val ids = mutableListOf<Int>()
            val proposals = CategoryPrompt.parseProposals(text, current.mapTo(mutableSetOf()) { it.name })
            for (proposal in proposals.take(count)) {
                when (val created = memoryService.createCategory(proposal.name, proposal.description)) {
                    is Either.Left -> Unit
                    is Either.Right -> ids += created.value.id
                }
            }
            BackfillOutcome.Created(ids)
        }
    }

    private fun failure(error: MemoryError): CategorizationResult.Failed =
        CategorizationResult.Failed(IllegalStateException("Category lookup failed: $error"))

    private sealed interface ScoringOutcome {
        data class Scored(val scores: Map<Int, Int>) : ScoringOutcome
        data class Stopped(val result: CategorizationResult) : ScoringOutcome
    }

    private sealed interface BackfillOutcome {
        data class Created(val ids: List<Int>) : BackfillOutcome
        data class Stopped(val result: CategorizationResult) : BackfillOutcome
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 10
    }
}
