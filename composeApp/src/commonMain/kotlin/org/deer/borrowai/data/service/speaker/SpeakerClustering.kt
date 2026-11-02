package org.deer.borrowai.data.service.speaker

/** Cosine distance under which two turns, or two clusters, belong to one speaker (section 9.6). */
internal const val CLUSTER_DISTANCE = 0.5

/** A speaker with less speech than this is noise and is dropped (section 7.2 step 5). */
internal const val MIN_SPEECH_MS = 1_500L

/** Most speakers kept in one recording (section 7.2 step 6). */
internal const val MAX_SPEAKERS = 8

/**
 * Groups speech turns into speakers. Returns, for each turn, its speaker index or -1 when the turn belongs to no kept
 * speaker. Speaker indexes are ordered by first appearance in the audio.
 *
 * Turns are merged by average linkage until the closest pair is farther apart than [CLUSTER_DISTANCE]. Clusters whose
 * centroids are that close are merged too. Speakers with less than [MIN_SPEECH_MS] of speech are dropped. Speakers past
 * [MAX_SPEAKERS] merge into the nearest kept speaker.
 */
internal fun clusterTurns(turns: List<SpeechTurn>): IntArray {
    val labels = IntArray(turns.size) { -1 }
    if (turns.isEmpty()) return labels
    val unit = turns.map { normalized(it.embedding) }
    val merged = mergeSimilarCentroids(averageLinkage(unit), unit)
    val withSpeech = merged.filter { speechMs(it, turns) >= MIN_SPEECH_MS }
    val kept = capSpeakers(withSpeech, turns, unit)
    kept.sortedBy { firstStartMs(it, turns) }.forEachIndexed { label, cluster ->
        for (turn in cluster) labels[turn] = label
    }
    return labels
}

/** Average-linkage agglomerative clustering over unit vectors. Stops at the first pair farther than [CLUSTER_DISTANCE]. */
private fun averageLinkage(unit: List<FloatArray>): List<MutableList<Int>> {
    val count = unit.size
    val distance = DoubleArray(count * count)
    for (i in 0 until count) {
        for (j in i + 1 until count) {
            val pairDistance = 1.0 - dot(unit[i], unit[j])
            distance[i * count + j] = pairDistance
            distance[j * count + i] = pairDistance
        }
    }
    val alive = BooleanArray(count) { true }
    val size = IntArray(count) { 1 }
    val members = List(count) { mutableListOf(it) }
    while (true) {
        var bestA = -1
        var bestB = -1
        var best = Double.POSITIVE_INFINITY
        for (a in 0 until count) {
            if (!alive[a]) continue
            for (b in a + 1 until count) {
                if (alive[b] && distance[a * count + b] < best) {
                    best = distance[a * count + b]
                    bestA = a
                    bestB = b
                }
            }
        }
        if (bestA < 0 || best > CLUSTER_DISTANCE) break
        // Lance-Williams update for average linkage: the distance from the merged cluster is the size-weighted mean.
        val sizeA = size[bestA].toDouble()
        val sizeB = size[bestB].toDouble()
        for (k in 0 until count) {
            if (!alive[k] || k == bestA || k == bestB) continue
            val updated = (sizeA * distance[bestA * count + k] + sizeB * distance[bestB * count + k]) / (sizeA + sizeB)
            distance[bestA * count + k] = updated
            distance[k * count + bestA] = updated
        }
        size[bestA] += size[bestB]
        members[bestA].addAll(members[bestB])
        members[bestB].clear()
        alive[bestB] = false
    }
    return members.filterIndexed { index, _ -> alive[index] }
}

/** Merges the two clusters with the closest centroids while they are within [CLUSTER_DISTANCE]. */
private fun mergeSimilarCentroids(clusters: List<MutableList<Int>>, unit: List<FloatArray>): List<MutableList<Int>> {
    val result = clusters.toMutableList()
    while (result.size > 1) {
        val centroids = result.map { cluster -> meanDirection(cluster.map { unit[it] }) }
        var bestI = -1
        var bestJ = -1
        var best = Double.POSITIVE_INFINITY
        for (i in centroids.indices) {
            for (j in i + 1 until centroids.size) {
                val centroidDistance = 1.0 - dot(centroids[i], centroids[j])
                if (centroidDistance < best) {
                    best = centroidDistance
                    bestI = i
                    bestJ = j
                }
            }
        }
        if (bestI < 0 || best > CLUSTER_DISTANCE) break
        result[bestI].addAll(result[bestJ])
        result.removeAt(bestJ)
    }
    return result
}

/** Keeps the [MAX_SPEAKERS] speakers with the most speech. Each other speaker joins the kept one with the closest centroid. */
private fun capSpeakers(
    clusters: List<MutableList<Int>>,
    turns: List<SpeechTurn>,
    unit: List<FloatArray>,
): List<MutableList<Int>> {
    if (clusters.size <= MAX_SPEAKERS) return clusters
    val ranked = clusters.sortedWith(
        compareByDescending<MutableList<Int>> { speechMs(it, turns) }.thenBy { firstStartMs(it, turns) },
    )
    val kept = ranked.take(MAX_SPEAKERS)
    val keptCentroids = kept.map { cluster -> meanDirection(cluster.map { unit[it] }) }
    for (extra in ranked.drop(MAX_SPEAKERS)) {
        val centroid = meanDirection(extra.map { unit[it] })
        val nearest = keptCentroids.indices.minBy { 1.0 - dot(keptCentroids[it], centroid) }
        kept[nearest].addAll(extra)
    }
    return kept
}

private fun speechMs(cluster: List<Int>, turns: List<SpeechTurn>): Long =
    unionLength(cluster.map { turns[it].toRange() })

private fun firstStartMs(cluster: List<Int>, turns: List<SpeechTurn>): Long =
    cluster.minOf { turns[it].startMs }
