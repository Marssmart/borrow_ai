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
    const val LLM_FILE_NAME = "llm-model.litertlm"

    /**
     * Files written by earlier catalog versions that the current runtimes cannot load:
     * Whisper.cpp GGML speech models and GGUF language models.
     */
    val OBSOLETE_FILE_NAMES = listOf("voice-model.bin", "live-voice-model.bin", "llm-model.gguf")

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
                displayName = "Qwen3 0.6B",
                url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/e6687177e00897731e7716267eebd4eba0dde7c2/Qwen3-0.6B.litertlm",
                revision = "e6687177e00897731e7716267eebd4eba0dde7c2",
                sizeBytes = 613_722_264L,
                sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
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
                displayName = "Gemma 4 E2B",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
                revision = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1",
                sizeBytes = 2_588_147_712L,
                sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
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
                displayName = "Gemma 4 E4B",
                url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm",
                revision = "2eee7ac325f20eb8c9ac1d0e972f7c84663062da",
                sizeBytes = 3_659_530_240L,
                sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
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
