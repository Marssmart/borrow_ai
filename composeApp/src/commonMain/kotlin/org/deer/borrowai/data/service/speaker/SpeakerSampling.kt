package org.deer.borrowai.data.service.speaker

import org.deer.borrowai.data.domain.MAX_SAMPLES_PER_OCCURRENCE

/** Sample length worth preferring, in milliseconds (section 9.6). */
internal const val MIN_SAMPLE_MS = 4_000L

/** Longest sample played back, in milliseconds (section 9.6). */
internal const val MAX_SAMPLE_MS = 8_000L

/** Longest pause that still belongs to one speaker's run (section 7.2 step 7). */
internal const val MAX_RUN_PAUSE_MS = 1_000L

/** A half-open time range in milliseconds from the start of the recording. */
internal data class TimeRange(val startMs: Long, val endMs: Long) {
    val lengthMs: Long
        get() = endMs - startMs
}

internal fun SpeechTurn.toRange(): TimeRange = TimeRange(startMs, endMs)

/** Total time covered by [ranges], counting overlapping time once. */
internal fun unionLength(ranges: List<TimeRange>): Long = mergeRanges(ranges, gapMs = 0L).sumOf { it.lengthMs }

/** Merges ranges that overlap or are at most [gapMs] apart. The result is sorted and disjoint. */
internal fun mergeRanges(ranges: List<TimeRange>, gapMs: Long): List<TimeRange> {
    val merged = mutableListOf<TimeRange>()
    for (range in ranges.sortedBy { it.startMs }) {
        val last = merged.lastOrNull()
        if (last != null && range.startMs - last.endMs <= gapMs) {
            merged[merged.lastIndex] = TimeRange(last.startMs, maxOf(last.endMs, range.endMs))
        } else {
            merged.add(range)
        }
    }
    return merged
}

/** The parts of [span] that no range in [cuts] covers. [cuts] must be sorted and disjoint, as [mergeRanges] returns. */
internal fun subtractRanges(span: TimeRange, cuts: List<TimeRange>): List<TimeRange> {
    val pieces = mutableListOf<TimeRange>()
    var cursor = span.startMs
    for (cut in cuts) {
        if (cut.endMs <= cursor) continue
        if (cut.startMs >= span.endMs) break
        if (cut.startMs > cursor) pieces.add(TimeRange(cursor, cut.startMs))
        cursor = maxOf(cursor, cut.endMs)
    }
    if (cursor < span.endMs) pieces.add(TimeRange(cursor, span.endMs))
    return pieces
}

/**
 * The sample for one speaker, given that speaker's own speech ([own]) and everyone else's ([others]). A sample never
 * covers time that another speaker talks in. The longest single-speaker run of at least [MIN_SAMPLE_MS] wins. If no run
 * is that long, the longest run wins. The sample starts at the run's start and is clipped to [MAX_SAMPLE_MS]. Returns
 * null when no part of the speaker's speech is free of other speakers.
 */
internal fun selectSample(own: List<TimeRange>, others: List<TimeRange>): TimeRange? {
    val runs = cleanRuns(own, others)
    val chosen = runs.filter { it.lengthMs >= MIN_SAMPLE_MS }.maxByOrNull { it.lengthMs }
        ?: runs.maxByOrNull { it.lengthMs }
        ?: return null
    return TimeRange(chosen.startMs, chosen.startMs + minOf(chosen.lengthMs, MAX_SAMPLE_MS))
}

/**
 * Selects up to [MAX_SAMPLES_PER_OCCURRENCE] clean runs, preferring runs at least [MIN_SAMPLE_MS] long. Each selected
 * range is clipped to [MAX_SAMPLE_MS], appears once, and is returned in recording order.
 */
internal fun selectSamples(own: List<TimeRange>, others: List<TimeRange>): List<TimeRange> {
    val runs = cleanRuns(own, others)
    val preferred = runs.filter { it.lengthMs >= MIN_SAMPLE_MS }
    val candidates = if (preferred.isNotEmpty()) preferred else runs
    return candidates
        .sortedWith(compareByDescending<TimeRange> { it.lengthMs }.thenBy { it.startMs })
        .take(MAX_SAMPLES_PER_OCCURRENCE)
        .map { TimeRange(it.startMs, it.startMs + minOf(it.lengthMs, MAX_SAMPLE_MS)) }
        .sortedBy { it.startMs }
}

private fun cleanRuns(own: List<TimeRange>, others: List<TimeRange>): List<TimeRange> {
    val spans = mergeRanges(own, gapMs = MAX_RUN_PAUSE_MS)
    val cuts = mergeRanges(others.filter { it.lengthMs > 0 }, gapMs = 0L)
    return spans.flatMap { subtractRanges(it, cuts) }.filter { it.lengthMs > 0 }
}
