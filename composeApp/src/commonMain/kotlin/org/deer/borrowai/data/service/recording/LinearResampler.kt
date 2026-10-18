package org.deer.borrowai.data.service.recording

/**
 * Streaming linear resampler for mono float samples. Each [push] emits the output samples that fall between the
 * previous input sample and the new one, so long recordings never need to be held in memory at once.
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
    private var lastIndex = -1L
    private var last = 0f
    private var nextOutput = 0.0

    fun push(sample: Float) {
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
}
