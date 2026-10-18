package org.deer.borrowai.data.service.recording

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun downsampleKeepsDcLevel() {
        val output = resample(32_000, 16_000, List(400) { 1f })

        assertEquals(1f, output.last(), absoluteTolerance = 1e-3f)
    }

    @Test
    fun downsampleKeepsInBandTone() {
        val output = resample(44_100, 16_000, tone(frequency = 1_000.0, sampleRate = 44_100, count = 44_100))

        assertTrue(output.drop(1_000).maxOf { abs(it) } > 0.9f)
    }

    @Test
    fun downsampleRejectsToneAboveOutputNyquist() {
        val output = resample(44_100, 16_000, tone(frequency = 10_000.0, sampleRate = 44_100, count = 44_100))

        assertTrue(output.drop(1_000).maxOf { abs(it) } < 0.1f)
    }

    private fun tone(frequency: Double, sampleRate: Int, count: Int): List<Float> =
        List(count) { n -> sin(2.0 * PI * frequency * n / sampleRate).toFloat() }

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
