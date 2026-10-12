package org.deer.borrowai

internal enum class ModelTier(val label: String) {
    LowMemory("LOW MEMORY"),
    Balanced("BALANCED"),
    HighPerformance("HIGH PERFORMANCE"),
}

internal data class MockModel(
    val id: String,
    val name: String,
    val tier: ModelTier,
    val size: String,
    val memory: String,
    val description: String,
)

internal object MockModels {
    val speech = listOf(
        MockModel(
            id = "speech-tiny",
            name = "Whisper Tiny · INT8",
            tier = ModelTier.LowMemory,
            size = "39 MB",
            memory = "~0.5 GB RAM",
            description = "Smallest footprint for older phones and tight storage.",
        ),
        MockModel(
            id = "speech-base",
            name = "Whisper Base · INT8",
            tier = ModelTier.Balanced,
            size = "74 MB",
            memory = "~1 GB RAM",
            description = "A practical balance of memory use and recognition quality.",
        ),
        MockModel(
            id = "speech-large",
            name = "Whisper Large Turbo · INT8",
            tier = ModelTier.HighPerformance,
            size = "1.6 GB",
            memory = "~4 GB RAM",
            description = "Best detail and responsiveness on high-end hardware.",
        ),
    )

    val language = listOf(
        MockModel(
            id = "llm-smollm",
            name = "SmolLM2 135M · Q4",
            tier = ModelTier.LowMemory,
            size = "100 MB",
            memory = "~0.5 GB RAM",
            description = "A compact option for devices with limited memory.",
        ),
        MockModel(
            id = "llm-llama-1b",
            name = "Llama 3.2 1B · Q4",
            tier = ModelTier.Balanced,
            size = "750 MB",
            memory = "~1.5 GB RAM",
            description = "Balanced generation speed and summary quality.",
        ),
        MockModel(
            id = "llm-qwen-3b",
            name = "Qwen 2.5 3B · Q4",
            tier = ModelTier.HighPerformance,
            size = "2 GB",
            memory = "~3.5 GB RAM",
            description = "Higher-quality summaries on newer, faster phones.",
        ),
    )
}
