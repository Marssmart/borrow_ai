package org.deer.borrowai.data.service.model

/** The hardware an on-device model runs on. */
enum class Accelerator { CPU, GPU }

/** Chooses the accelerator for a model role. A pure function, so every decision is unit-testable. */
object AcceleratorSelector {
    /**
     * The accelerator for [role] on a device of [tier]. Only the LLM uses the GPU, and only on the MID and LATEST tiers,
     * because GPU backends use more memory than the LEGACY tier can spare. Speech stays on the CPU: the prebuilt
     * sherpa-onnx library has no GPU provider, and live transcription is latency-sensitive.
     */
    fun select(role: ModelRole, tier: ModelTier, useGpuAcceleration: Boolean): Accelerator = when {
        !useGpuAcceleration -> Accelerator.CPU
        role == ModelRole.LLM && tier != ModelTier.LEGACY -> Accelerator.GPU
        else -> Accelerator.CPU
    }
}
