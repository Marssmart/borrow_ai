package org.deer.borrowai.data.service.recording

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Streaming linear resampler for mono float samples. Each [push] emits the output samples that fall between the
 * previous input sample and the new one, so long recordings never need to be held in memory at once.
 *
 * When downsampling, the input is first low-passed below the output's 8 kHz Nyquist limit so frequencies that would
 * otherwise fold back into the speech band are removed.
 */
internal class LinearResampler(
    sourceRate: Int,
    targetRate: Int,
    private val emit: (Float) -> Unit,
) {
    init {
        require(sourceRate > 0 && targetRate > 0) { "sample rates must be positive" }
    }

    private val step = sourceRate.toDouble() / targetRate
    private val antiAlias: List<Biquad> = if (sourceRate > targetRate) {
        val cutoff = ANTI_ALIAS_CUTOFF_RATIO * targetRate
        // Four cascaded second-order sections form an 8th-order Butterworth low-pass.
        ANTI_ALIAS_Q.map { q -> Biquad(sourceRate.toDouble(), cutoff, q) }
    } else {
        emptyList()
    }
    private var lastIndex = -1L
    private var last = 0f
    private var nextOutput = 0.0

    fun push(input: Float) {
        val sample = antiAlias.fold(input.toDouble()) { value, filter -> filter.process(value) }.toFloat()
        lastIndex++
        while (nextOutput <= lastIndex) {
            val value = if (lastIndex == 0L) {
                sample
            } else {
                val fraction = (nextOutput - (lastIndex - 1)).toFloat()
                last * (1f - fraction) + sample * fraction
            }
            emit(value)
            nextOutput += step
        }
        last = sample
    }

    private companion object {
        // Leaves a margin below the 8 kHz Nyquist limit of 16 kHz output for the filter's roll-off.
        const val ANTI_ALIAS_CUTOFF_RATIO = 0.45
        // Butterworth section Q values for order 8: 1 / (2 cos((2k - 1) * pi / 16)), k = 1..4.
        val ANTI_ALIAS_Q = listOf(0.5098, 0.6013, 0.8999, 2.5629)
    }
}

/** Second-order low-pass section (RBJ cookbook), processing one sample at a time. */
private class Biquad(sampleRate: Double, cutoff: Double, q: Double) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val omega = 2.0 * PI * cutoff / sampleRate
        val alpha = sin(omega) / (2.0 * q)
        val cosine = cos(omega)
        val a0 = 1.0 + alpha
        b0 = (1.0 - cosine) / 2.0 / a0
        b1 = (1.0 - cosine) / a0
        b2 = (1.0 - cosine) / 2.0 / a0
        a1 = -2.0 * cosine / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}
