package org.deer.borrowai.data.service.recording

import kotlin.test.Test
import kotlin.test.assertEquals

class LinearResamplerTest {
    private fun resample(sourceRate: Int, targetRate: Int, input: List<Float>): List<Float> {
        val output = mutableListOf<Float>()
        val resampler = LinearResampler(sourceRate, targetRate) { output += it }
        input.forEach(resampler::push)
        return output
    }

    @Test
    fun identityRatePassesSamplesThrough() {
        val input = listOf(0.1f, -0.2f, 0.3f, 0.4f)

        assertEquals(input, resample(16_000, 16_000, input))
    }

    @Test
    fun downsampleByTwoKeepsEverySecondSample() {
        val input = listOf(0f, 1f, 2f, 3f, 4f, 5f)

        assertEquals(listOf(0f, 2f, 4f), resample(32_000, 16_000, input))
    }

    @Test
    fun upsampleByTwoInterpolatesMidpoints() {
        val input = listOf(0f, 1f, 2f)

        assertEquals(listOf(0f, 0.5f, 1f, 1.5f, 2f), resample(8_000, 16_000, input))
    }

    @Test
    fun emptyInputEmitsNothing() {
        assertEquals(emptyList(), resample(44_100, 16_000, emptyList()))
    }
}
