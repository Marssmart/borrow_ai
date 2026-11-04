package org.deer.borrowai.data.service.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AcceleratorSelectorTest {
    @Test
    fun llmUsesTheGpuOnMidAndLatestWhenEnabled() {
        listOf(ModelTier.MID, ModelTier.LATEST).forEach { tier ->
            assertEquals(Accelerator.GPU, AcceleratorSelector.select(ModelRole.LLM, tier, useGpuAcceleration = true))
        }
    }

    @Test
    fun llmUsesTheCpuOnLegacy() {
        assertEquals(Accelerator.CPU, AcceleratorSelector.select(ModelRole.LLM, ModelTier.LEGACY, true))
    }

    @Test
    fun disablingTheSettingForcesTheCpu() {
        ModelTier.entries.forEach { tier ->
            assertEquals(Accelerator.CPU, AcceleratorSelector.select(ModelRole.LLM, tier, false))
        }
    }

    @Test
    fun speechStaysOnTheCpu() {
        listOf(ModelRole.LIVE_VOICE, ModelRole.VOICE).forEach { role ->
            ModelTier.entries.forEach { tier ->
                assertEquals(Accelerator.CPU, AcceleratorSelector.select(role, tier, true))
            }
        }
    }
}
