package org.deer.borrowai.data.service.model

enum class ModelRole {
    LIVE_VOICE,
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

/**
 * [live] is a small, fast speech model for transcribing while recording.
 * [voice] is the tier's high-quality speech model for transcribing saved memories.
 * Speech models are multi-file (encoder, decoder, tokens), so each is a list of files.
 */
data class TierModels(
    val tier: ModelTier,
    val live: List<ModelSpec>,
    val voice: List<ModelSpec>,
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
    /** Static file names per role. Speech models also use `<prefix>-decoder.int8.onnx` and `<prefix>-tokens.txt`. */
    const val LIVE_FILE_NAME = "live-encoder.int8.onnx"
    const val VOICE_FILE_NAME = "voice-encoder.int8.onnx"
    const val LLM_FILE_NAME = "llm-model.gguf"

    private const val TOKENS_SIZE_BYTES = 816_730L
    private const val TOKENS_SHA256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"

    private val tinyLive: List<ModelSpec> = tiny(role = ModelRole.LIVE_VOICE, localPrefix = "live")
    private val tinyVoice: List<ModelSpec> = tiny(role = ModelRole.VOICE, localPrefix = "voice")

    private val models: Map<ModelTier, TierModels> = mapOf(
        ModelTier.LEGACY to TierModels(
            tier = ModelTier.LEGACY,
            // Tiny serves as both models on legacy devices, so it is downloaded once under the voice file names.
            live = tiny(role = ModelRole.LIVE_VOICE, localPrefix = "voice"),
            voice = tinyVoice,
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
            live = tinyLive,
            voice = whisper(
                displayName = "Whisper Base",
                role = ModelRole.VOICE,
                localPrefix = "voice",
                remoteName = "base",
                revision = "bb53ee204431c90d314c1cc08d28d23e5b7927cc",
                encoderSizeBytes = 29_120_534L,
                encoderSha256 = "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
                decoderSizeBytes = 130_672_026L,
                decoderSha256 = "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
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
            live = tinyLive,
            voice = whisper(
                displayName = "Whisper Large v3",
                role = ModelRole.VOICE,
                localPrefix = "voice",
                remoteName = "large-v3",
                revision = "2a6507094dd6020d939d78e3f1834a1d06267fca",
                encoderSizeBytes = 766_671_985L,
                encoderSha256 = "d531cf17248acc43e8c09b472a0877055e770877857a5332fc1304b36534ec85",
                decoderSizeBytes = 1_008_265_203L,
                decoderSha256 = "ebc6bfd88e162a46cb3edee8a7e727e1dcbc65cabecb19e2573695e4d495e1af",
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

    private fun tiny(role: ModelRole, localPrefix: String): List<ModelSpec> = whisper(
        displayName = "Whisper Tiny",
        role = role,
        localPrefix = localPrefix,
        remoteName = "tiny",
        revision = "65176e2deb88badc814a94058666cadccc29b61c",
        encoderSizeBytes = 12_937_772L,
        encoderSha256 = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
        decoderSizeBytes = 89_855_401L,
        decoderSha256 = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
    )

    /**
     * A sherpa-onnx Whisper int8 model from the csukuangfj/sherpa-onnx-whisper-* repos, pinned to [revision].
     * The tokens file is identical across sizes, so every model shares its digest.
     */
    private fun whisper(
        displayName: String,
        role: ModelRole,
        localPrefix: String,
        remoteName: String,
        revision: String,
        encoderSizeBytes: Long,
        encoderSha256: String,
        decoderSizeBytes: Long,
        decoderSha256: String,
    ): List<ModelSpec> {
        val base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$remoteName/resolve/$revision"
        return listOf(
            ModelSpec(
                displayName = displayName,
                role = role,
                fileName = "$localPrefix-encoder.int8.onnx",
                downloadUrl = "$base/$remoteName-encoder.int8.onnx",
                revision = revision,
                sizeBytes = encoderSizeBytes,
                sha256 = encoderSha256,
            ),
            ModelSpec(
                displayName = displayName,
                role = role,
                fileName = "$localPrefix-decoder.int8.onnx",
                downloadUrl = "$base/$remoteName-decoder.int8.onnx",
                revision = revision,
                sizeBytes = decoderSizeBytes,
                sha256 = decoderSha256,
            ),
            ModelSpec(
                displayName = displayName,
                role = role,
                fileName = "$localPrefix-tokens.txt",
                downloadUrl = "$base/$remoteName-tokens.txt",
                revision = revision,
                sizeBytes = TOKENS_SIZE_BYTES,
                sha256 = TOKENS_SHA256,
            ),
        )
    }

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
