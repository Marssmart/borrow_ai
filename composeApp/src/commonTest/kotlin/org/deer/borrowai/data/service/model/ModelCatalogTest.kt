package org.deer.borrowai.data.service.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ModelCatalogTest {
    private val gib = ModelTierSelector.BYTES_PER_GIB

    @Test
    fun tierBoundariesUseReportedRam() {
        assertEquals(ModelTier.LEGACY, ModelTierSelector.select(0L))
        assertEquals(ModelTier.LEGACY, ModelTierSelector.select(2L * gib))
        assertEquals(ModelTier.LEGACY, ModelTierSelector.select(5L * gib - 1))
        assertEquals(ModelTier.MID, ModelTierSelector.select(5L * gib))
        assertEquals(ModelTier.MID, ModelTierSelector.select(10L * gib - 1))
        assertEquals(ModelTier.LATEST, ModelTierSelector.select(10L * gib))
        assertEquals(ModelTier.LATEST, ModelTierSelector.select(16L * gib))
    }

    @Test
    fun negativeRamIsRejected() {
        assertFailsWith<IllegalArgumentException> { ModelTierSelector.select(-1L) }
    }

    @Test
    fun eachTierPicksItsVoiceAndLlmModels() {
        val legacy = ModelCatalog.modelsFor(ModelTier.LEGACY)
        assertEquals("Whisper Tiny", legacy.voice.displayName)
        assertEquals("Gemma 2B", legacy.llm.displayName)

        val mid = ModelCatalog.modelsFor(ModelTier.MID)
        assertEquals("Whisper Base", mid.voice.displayName)
        assertEquals("Gemma 7B", mid.llm.displayName)

        val latest = ModelCatalog.modelsFor(ModelTier.LATEST)
        assertEquals("Whisper Large v3", latest.voice.displayName)
        assertEquals("Gemma 9B", latest.llm.displayName)
    }

    @Test
    fun ramSelectsTheMatchingTierModels() {
        assertEquals(ModelTier.LEGACY, ModelCatalog.modelsForRam(4L * gib).tier)
        assertEquals(ModelTier.MID, ModelCatalog.modelsForRam(8L * gib).tier)
        assertEquals(ModelTier.LATEST, ModelCatalog.modelsForRam(12L * gib).tier)
    }

    @Test
    fun catalogEntriesAreHttpsWithPositiveSizesAndStaticFileNames() {
        val allModels = ModelTier.entries.flatMap { tier ->
            ModelCatalog.modelsFor(tier).let { listOf(it.voice, it.llm) }
        }
        allModels.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://"), model.downloadUrl)
            assertTrue(model.sizeBytes > 0L, model.displayName)
        }
        ModelTier.entries.forEach { tier ->
            val models = ModelCatalog.modelsFor(tier)
            assertEquals(ModelRole.VOICE, models.voice.role)
            assertEquals(ModelRole.LLM, models.llm.role)
            assertEquals(ModelCatalog.VOICE_FILE_NAME, models.voice.fileName)
            assertEquals(ModelCatalog.LLM_FILE_NAME, models.llm.fileName)
        }
        assertNotEquals(ModelCatalog.VOICE_FILE_NAME, ModelCatalog.LLM_FILE_NAME)
    }

    @Test
    fun liveModelSharesTheVoiceFileOnlyOnLegacyTier() {
        val legacy = ModelCatalog.modelsFor(ModelTier.LEGACY)
        assertEquals(ModelRole.LIVE_VOICE, legacy.live.role)
        assertEquals(ModelCatalog.VOICE_FILE_NAME, legacy.live.fileName)
        assertEquals(legacy.voice.sha256, legacy.live.sha256)

        listOf(ModelTier.MID, ModelTier.LATEST).forEach { tier ->
            val models = ModelCatalog.modelsFor(tier)
            assertEquals(ModelRole.LIVE_VOICE, models.live.role)
            assertEquals(ModelCatalog.LIVE_FILE_NAME, models.live.fileName)
            assertNotEquals(models.voice.fileName, models.live.fileName)
        }
    }

    @Test
    fun everyModelIsPinnedToAnImmutableRevisionWithAVerifiableDigest() {
        val hex = Regex("[0-9a-f]+")
        ModelTier.entries.forEach { tier ->
            val models = ModelCatalog.modelsFor(tier)
            listOf(models.voice, models.llm).forEach { model ->
                assertEquals(40, model.revision.length, model.displayName)
                assertTrue(hex.matches(model.revision), model.displayName)
                assertEquals(64, model.sha256.length, model.displayName)
                assertTrue(hex.matches(model.sha256), model.displayName)
                assertTrue(model.downloadUrl.startsWith("https://huggingface.co/"), model.displayName)
                assertTrue(model.downloadUrl.contains("/resolve/${model.revision}/"), model.displayName)
            }
        }
    }
}
