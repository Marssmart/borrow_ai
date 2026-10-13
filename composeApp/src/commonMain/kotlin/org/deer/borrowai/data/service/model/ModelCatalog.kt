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

/**
 * A downloadable model. The URL is pinned to [revision] and the bytes must match [sha256] and [sizeBytes].
 * [fileName] is static per role so the next launch can find it.
 */
data class ModelSpec(
    val displayName: String,
    val role: ModelRole,
    val fileName: String,
    val downloadUrl: String,
    val revision: String,
    val sizeBytes: Long,
    val sha256: String,
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

    private const val WHISPER_CPP_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"

    private val models: Map<ModelTier, TierModels> = mapOf(
        ModelTier.LEGACY to TierModels(
            tier = ModelTier.LEGACY,
            voice = voice(
                displayName = "Whisper Tiny",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$WHISPER_CPP_REVISION/ggml-tiny.bin",
                revision = WHISPER_CPP_REVISION,
                sizeBytes = 77_691_713L,
                sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            ),
            llm = llm(
                displayName = "Gemma 2B",
                url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/855f67caed130e1befc571b52bd181be2e858883/gemma-2-2b-it-Q4_K_M.gguf",
                revision = "855f67caed130e1befc571b52bd181be2e858883",
                sizeBytes = 1_708_582_752L,
                sha256 = "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787",
            ),
        ),
        ModelTier.MID to TierModels(
            tier = ModelTier.MID,
            voice = voice(
                displayName = "Whisper Base",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$WHISPER_CPP_REVISION/ggml-base.bin",
                revision = WHISPER_CPP_REVISION,
                sizeBytes = 147_951_465L,
                sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            ),
            llm = llm(
                displayName = "Gemma 7B",
                url = "https://huggingface.co/bartowski/gemma-1.1-7b-it-GGUF/resolve/977053969545d1abfe364d0161f37e0de22fa54a/gemma-1.1-7b-it-Q4_K_M.gguf",
                revision = "977053969545d1abfe364d0161f37e0de22fa54a",
                sizeBytes = 5_329_759_200L,
                sha256 = "47821da72ee9e80b6fd43c6190ad751b485fb61fa5664590f7a73246bcd8332e",
            ),
        ),
        ModelTier.LATEST to TierModels(
            tier = ModelTier.LATEST,
            voice = voice(
                displayName = "Whisper Large v3",
                url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$WHISPER_CPP_REVISION/ggml-large-v3.bin",
                revision = WHISPER_CPP_REVISION,
                sizeBytes = 3_095_033_483L,
                sha256 = "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2",
            ),
            llm = llm(
                displayName = "Gemma 9B",
                url = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/d731033f3dc4018261fd39896e50984d398b4ac5/gemma-2-9b-it-Q4_K_M.gguf",
                revision = "d731033f3dc4018261fd39896e50984d398b4ac5",
                sizeBytes = 5_761_057_728L,
                sha256 = "13b2a7b4115bbd0900162edcebe476da1ba1fc24e718e8b40d32f6e300f56dfe",
            ),
        ),
    )

    fun modelsFor(tier: ModelTier): TierModels = models.getValue(tier)

    fun modelsForRam(totalRamBytes: Long): TierModels = modelsFor(ModelTierSelector.select(totalRamBytes))

    private fun voice(
        displayName: String,
        url: String,
        revision: String,
        sizeBytes: Long,
        sha256: String,
    ) = ModelSpec(
        displayName = displayName,
        role = ModelRole.VOICE,
        fileName = VOICE_FILE_NAME,
        downloadUrl = url,
        revision = revision,
        sizeBytes = sizeBytes,
        sha256 = sha256,
    )

    private fun llm(
        displayName: String,
        url: String,
        revision: String,
        sizeBytes: Long,
        sha256: String,
    ) = ModelSpec(
        displayName = displayName,
        role = ModelRole.LLM,
        fileName = LLM_FILE_NAME,
        downloadUrl = url,
        revision = revision,
        sizeBytes = sizeBytes,
        sha256 = sha256,
    )
}
