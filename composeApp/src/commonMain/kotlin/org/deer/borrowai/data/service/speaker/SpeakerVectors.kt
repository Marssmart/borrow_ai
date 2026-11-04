package org.deer.borrowai.data.service.speaker

import kotlin.math.sqrt

/** [vector] scaled to unit length. A zero vector stays zero, so it is not similar to anything. */
internal fun normalized(vector: FloatArray): FloatArray {
    var squares = 0.0
    for (value in vector) squares += value.toDouble() * value
    val norm = sqrt(squares)
    if (norm == 0.0) return FloatArray(vector.size)
    return FloatArray(vector.size) { index -> (vector[index] / norm).toFloat() }
}

/** The dot product. For unit vectors this is the cosine similarity. */
internal fun dot(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "Embeddings must have the same dimension." }
    var sum = 0.0
    for (index in a.indices) sum += a[index].toDouble() * b[index]
    return sum
}

/** The normalized mean of [unitVectors]. The list must not be empty. */
internal fun meanDirection(unitVectors: List<FloatArray>): FloatArray {
    val size = unitVectors.first().size
    val sum = DoubleArray(size)
    for (vector in unitVectors) {
        for (index in 0 until size) sum[index] += vector[index].toDouble()
    }
    return normalized(FloatArray(size) { index -> sum[index].toFloat() })
}
