package org.deer.borrowai.data.service.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals("Whisper Tiny", legacy.voice.first().displayName)
        assertEquals("Gemma 2B", legacy.llm.displayName)

        val mid = ModelCatalog.modelsFor(ModelTier.MID)
        assertEquals("Whisper Base", mid.voice.first().displayName)
        assertEquals("Gemma 7B", mid.llm.displayName)

        val latest = ModelCatalog.modelsFor(ModelTier.LATEST)
        assertEquals("Whisper Large v3", latest.voice.first().displayName)
        assertEquals("Gemma 9B", latest.llm.displayName)
    }

    @Test
    fun speechModelsAreEncoderDecoderAndTokensFiles() {
        ModelTier.entries.forEach { tier ->
            val models = ModelCatalog.modelsFor(tier)
            listOf(models.live, models.voice).forEach { files ->
                assertEquals(3, files.size, tier.name)
                assertTrue(files.any { it.fileName.endsWith("-encoder.int8.onnx") }, tier.name)
                assertTrue(files.any { it.fileName.endsWith("-decoder.int8.onnx") }, tier.name)
                assertTrue(files.any { it.fileName.endsWith("-tokens.txt") }, tier.name)
            }
        }
    }

    @Test
    fun ramSelectsTheMatchingTierModels() {
        assertEquals(ModelTier.LEGACY, ModelCatalog.modelsForRam(4L * gib).tier)
        assertEquals(ModelTier.MID, ModelCatalog.modelsForRam(8L * gib).tier)
        assertEquals(ModelTier.LATEST, ModelCatalog.modelsForRam(12L * gib).tier)
    }

    @Test
    fun catalogEntriesAreHttpsWithPositiveSizesAndStaticFileNames() {
        ModelTier.entries.forEach { tier ->
            val models = ModelCatalog.modelsFor(tier)
            (models.live + models.voice + listOf(models.llm)).forEach { model ->
                assertTrue(model.downloadUrl.startsWith("https://"), model.downloadUrl)
                assertTrue(model.sizeBytes > 0L, model.displayName)
            }
            models.voice.forEach { assertEquals(ModelRole.VOICE, it.role, it.displayName) }
            models.live.forEach { assertEquals(ModelRole.LIVE_VOICE, it.role, it.displayName) }
            assertEquals(ModelRole.LLM, models.llm.role)
            assertEquals(ModelCatalog.LLM_FILE_NAME, models.llm.fileName)
            assertEquals(models.voice.map { it.fileName }.distinct().size, models.voice.size)
        }
    }

    @Test
    fun liveModelSharesTheVoiceFilesOnlyOnLegacyTier() {
        val legacy = ModelCatalog.modelsFor(ModelTier.LEGACY)
        assertEquals(legacy.voice.map { it.fileName }, legacy.live.map { it.fileName })
        assertEquals(legacy.voice.map { it.sha256 }, legacy.live.map { it.sha256 })

        listOf(ModelTier.MID, ModelTier.LATEST).forEach { tier ->
            val models = ModelCatalog.modelsFor(tier)
            assertTrue(models.live.none { it.fileName in models.voice.map { v -> v.fileName } }, tier.name)
        }
    }

    @Test
    fun everyModelIsPinnedToAnImmutableRevisionWithAVerifiableDigest() {
        val hex = Regex("[0-9a-f]+")
        ModelTier.entries.forEach { tier ->
            val models = ModelCatalog.modelsFor(tier)
            (models.live + models.voice + listOf(models.llm)).forEach { model ->
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
