package org.deer.borrowai.data.service.category

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.FakeMemoryService

class DefaultMemoryCategorizerTest {
    private class ScriptedModel(
        private val reply: suspend (systemInstruction: String, userMessage: String) -> CategoryModelReply,
    ) : CategoryModel {
        val scoringCalls = mutableListOf<String>()
        val backfillCalls = mutableListOf<String>()

        override suspend fun generate(systemInstruction: String, userMessage: String): CategoryModelReply {
            when (systemInstruction) {
                CategoryPrompt.SCORING_SYSTEM_INSTRUCTION -> scoringCalls += userMessage
                CategoryPrompt.BACKFILL_SYSTEM_INSTRUCTION -> backfillCalls += userMessage
                else -> error("Unexpected system instruction")
            }
            return reply(systemInstruction, userMessage)
        }
    }

    /** Scores every category in the batch with [score] and answers backfill with [backfill]. */
    private fun model(
        score: (Int) -> Int = { 0 },
        backfill: CategoryModelReply = CategoryModelReply.Text(""),
    ) = ScriptedModel { system, user ->
        if (system == CategoryPrompt.SCORING_SYSTEM_INSTRUCTION) {
            CategoryModelReply.Text(scoredIds(user).joinToString("\n") { "$it|${score(it)}" })
        } else {
            backfill
        }
    }

    private fun scoredIds(scoringMessage: String): List<Int> =
        scoringMessage.substringAfter("Categories:")
            .lineSequence()
            .mapNotNull { Regex("^(\\d+)\\. ").find(it.trim())?.groupValues?.get(1)?.toInt() }
            .toList()

    private suspend fun service(count: Int): FakeMemoryService {
        val service = FakeMemoryService(epochClock = EpochClock { 1_800_000_000_000L })
        repeat(count) { service.createCategory("Cat${it + 1}", "Description ${it + 1}") }
        return service
    }

    @Test
    fun `blank transcription is empty without calling the model`() = runTest {
        val model = model()

        val result = DefaultMemoryCategorizer(service(3), model).categorize("   ")

        assertEquals(CategorizationResult.Empty, result)
        assertTrue(model.scoringCalls.isEmpty() && model.backfillCalls.isEmpty())
    }

    @Test
    fun `scores masters in batches of the configured size`() = runTest {
        val model = model()

        DefaultMemoryCategorizer(service(25), model, batchSize = 10).categorize("note")

        assertEquals(listOf(10, 10, 5), model.scoringCalls.map { scoredIds(it).size })
    }

    @Test
    fun `keeps matches at or above the threshold sorted by score and capped at three`() = runTest {
        val scores = mapOf(1 to 95, 2 to 80, 3 to 75, 4 to 74, 5 to 90)
        val model = model(score = { scores.getValue(it) })

        val result = DefaultMemoryCategorizer(service(5), model).categorize("note")

        assertEquals(CategorizationResult.Categories(listOf(1, 5, 2)), result)
        assertTrue(model.backfillCalls.isEmpty())
    }

    @Test
    fun `backfills the remaining slots when fewer than three categories match`() = runTest {
        val model = model(
            score = { if (it == 1) 80 else 50 },
            backfill = CategoryModelReply.Text("Ocean|Waves and sea"),
        )
        val service = service(2)

        val result = DefaultMemoryCategorizer(service, model).categorize("note")

        assertEquals(CategorizationResult.Categories(listOf(1, 3)), result)
        assertEquals(1, model.backfillCalls.size)
        assertTrue(model.backfillCalls.single().contains("Suggest up to 2 new categories."))
        assertEquals("Ocean", service.findCategories().getOrNull()?.last()?.name)
    }

    @Test
    fun `backfill fills up to three categories when nothing matches`() = runTest {
        val model = model(
            backfill = CategoryModelReply.Text("A|first\nB|second\nC|third\nD|fourth"),
        )

        val result = DefaultMemoryCategorizer(service(2), model).categorize("note")

        assertEquals(CategorizationResult.Categories(listOf(3, 4, 5)), result)
        assertTrue(model.backfillCalls.single().contains("Suggest up to 3 new categories."))
    }

    @Test
    fun `falls back to the highest-scoring master when backfill yields nothing`() = runTest {
        val model = model(score = { if (it == 1) 40 else 60 })

        val result = DefaultMemoryCategorizer(service(2), model).categorize("note")

        assertEquals(CategorizationResult.Categories(listOf(2)), result)
    }

    @Test
    fun `backfills the first category when there are no masters`() = runTest {
        val model = model(backfill = CategoryModelReply.Text("Ocean|Waves and sea"))

        val result = DefaultMemoryCategorizer(service(0), model).categorize("note")

        assertEquals(CategorizationResult.Categories(listOf(1)), result)
        assertTrue(model.scoringCalls.isEmpty())
    }

    @Test
    fun `fails when there are no masters and backfill yields nothing`() = runTest {
        val result = DefaultMemoryCategorizer(service(0), model()).categorize("note")

        assertIs<CategorizationResult.Failed>(result)
    }

    @Test
    fun `keeps matched categories when backfill fails`() = runTest {
        val model = model(
            score = { if (it == 1) 80 else 0 },
            backfill = CategoryModelReply.Failed(IllegalStateException("boom")),
        )

        val result = DefaultMemoryCategorizer(service(2), model).categorize("note")

        assertEquals(CategorizationResult.Categories(listOf(1)), result)
    }

    @Test
    fun `concurrent backfills do not create the same category twice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = ScriptedModel { _, _ ->
            gate.await()
            CategoryModelReply.Text("Ada|Pioneer of computing")
        }
        val service = service(0)
        val categorizer = DefaultMemoryCategorizer(service, model)

        val first = async { categorizer.categorize("note") }
        val second = async { categorizer.categorize("note") }
        advanceUntilIdle()
        assertEquals(2, model.backfillCalls.size)

        gate.complete(Unit)
        val firstResult = first.await()
        second.await()

        assertIs<CategorizationResult.Categories>(firstResult)
        assertEquals(listOf("Ada"), service.findCategories().getOrNull()!!.map { it.name })
    }

    @Test
    fun `reports model missing from backfill when nothing else was chosen`() = runTest {
        val result = DefaultMemoryCategorizer(service(2), model(backfill = CategoryModelReply.ModelMissing))
            .categorize("note")

        assertEquals(CategorizationResult.ModelMissing, result)
    }

    @Test
    fun `reports model missing from scoring and stops`() = runTest {
        val model = ScriptedModel { _, _ -> CategoryModelReply.ModelMissing }

        val result = DefaultMemoryCategorizer(service(3), model).categorize("note")

        assertEquals(CategorizationResult.ModelMissing, result)
        assertEquals(1, model.scoringCalls.size)
        assertTrue(model.backfillCalls.isEmpty())
    }

    @Test
    fun `fails when scoring throws and stops`() = runTest {
        val model = ScriptedModel { _, _ -> CategoryModelReply.Failed(IllegalStateException("boom")) }

        val result = DefaultMemoryCategorizer(service(3), model).categorize("note")

        assertIs<CategorizationResult.Failed>(result)
        assertEquals(1, model.scoringCalls.size)
        assertTrue(model.backfillCalls.isEmpty())
    }

    @Test
    fun `fails when the scoring reply has no usable scores`() = runTest {
        val model = ScriptedModel { _, _ -> CategoryModelReply.Text("I cannot score these.") }

        val result = DefaultMemoryCategorizer(service(3), model).categorize("note")

        assertIs<CategorizationResult.Failed>(result)
        assertTrue(model.backfillCalls.isEmpty())
    }
}
