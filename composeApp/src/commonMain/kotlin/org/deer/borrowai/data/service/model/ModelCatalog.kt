package org.deer.borrowai.data.service.model

enum class ModelRole {
    VOICE,
    LLM,
}

enum class ModelTier {
    LEGACY,
    MID,
    LATEST,
}

/** A downloadable model. [fileName] is static per role so the next launch can find it. */
data class ModelSpec(
    val displayName: String,
    val role: ModelRole,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

data class TierModels(
    val tier: ModelTier,
    val voice: ModelSpec,
    val llm: ModelSpec,
)

object ModelTierSelector {
    const val BYTES_PER_GIB: Long = 1024L * 1024L * 1024L
    const val MID_TIER_MIN_RAM_BYTES: Long = 5L * BYTES_PER_GIB
    const val LATEST_TIER_MIN_RAM_BYTES: Long = 10L * BYTES_PER_GIB

    /** Tiers use the RAM the OS reports, which is below the marketed size. */
    fun select(totalRamBytes: Long): ModelTier {
        require(totalRamBytes >= 0L) { "Total RAM cannot be negative." }
        return when {
            totalRamBytes < MID_TIER_MIN_RAM_BYTES -> ModelTier.LEGACY
            totalRamBytes < LATEST_TIER_MIN_RAM_BYTES -> ModelTier.MID
            else -> ModelTier.LATEST
        }
    }
}

object ModelCatalog {
    const val MODEL_DIRECTORY_NAME = "models"
    const val VOICE_FILE_NAME = "voice-model.bin"
    const val LLM_FILE_NAME = "llm-model.gguf"

    private val models: Map<ModelTier, TierModels> = mapOf(
        ModelTier.LEGACY to TierModels(
            tier = ModelTier.LEGACY,
            voice = voice(
                displayName = "Whisper Tiny",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
                sizeBytes = 77_691_713L,
            ),
            llm = llm(
                displayName = "Gemma 2B",
                url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
                sizeBytes = 1_708_582_752L,
            ),
        ),
        ModelTier.MID to TierModels(
            tier = ModelTier.MID,
            voice = voice(
                displayName = "Whisper Base",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
                sizeBytes = 147_951_465L,
            ),
            llm = llm(
                displayName = "Gemma 7B",
                url = "https://huggingface.co/bartowski/gemma-1.1-7b-it-GGUF/resolve/main/gemma-1.1-7b-it-Q4_K_M.gguf",
                sizeBytes = 5_329_759_200L,
            ),
        ),
        ModelTier.LATEST to TierModels(
            tier = ModelTier.LATEST,
            voice = voice(
                displayName = "Whisper Large v3",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin",
                sizeBytes = 3_095_033_483L,
            ),
            llm = llm(
                displayName = "Gemma 9B",
                url = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
                sizeBytes = 5_761_057_728L,
            ),
        ),
    )

    fun modelsFor(tier: ModelTier): TierModels = models.getValue(tier)

    fun modelsForRam(totalRamBytes: Long): TierModels = modelsFor(ModelTierSelector.select(totalRamBytes))

    private fun voice(displayName: String, url: String, sizeBytes: Long) = ModelSpec(
        displayName = displayName,
        role = ModelRole.VOICE,
        fileName = VOICE_FILE_NAME,
        downloadUrl = url,
        sizeBytes = sizeBytes,
    )

    private fun llm(displayName: String, url: String, sizeBytes: Long) = ModelSpec(
        displayName = displayName,
        role = ModelRole.LLM,
        fileName = LLM_FILE_NAME,
        downloadUrl = url,
        sizeBytes = sizeBytes,
    )
}
