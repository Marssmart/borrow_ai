package org.deer.borrowai.data.service.background

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Work the app does without the user asking for it. The order here is the order the indicator lists them in. */
enum class BackgroundActivity {
    MODEL_DOWNLOAD,
    TRANSCRIPTION,
    SPEAKER_ANALYSIS,
    SUMMARY,
    CATEGORIZATION,
    CLEANUP,
}

/**
 * Counts the units of background work in progress. Each job wraps one unit of work in [track], so an activity is
 * active only while work is actually running, not during an idle periodic sweep. Counting makes overlapping units of
 * the same activity safe: the activity stays active until the last one ends.
 */
class BackgroundActivityTracker {
    private val counts = MutableStateFlow<Map<BackgroundActivity, Int>>(emptyMap())

    /** The activities with at least one unit of work running. Emits only when that set changes. */
    val activities: Flow<Set<BackgroundActivity>> = counts
        .map { it.keys.toSet() }
        .distinctUntilChanged()

    /** Marks [activity] active while [block] runs. The mark is removed when [block] returns, throws, or is cancelled. */
    suspend fun <T> track(activity: BackgroundActivity, block: suspend () -> T): T {
        counts.update { current -> current + (activity to (current[activity] ?: 0) + 1) }
        try {
            return block()
        } finally {
            counts.update { current ->
                val remaining = (current[activity] ?: 1) - 1
                if (remaining > 0) current + (activity to remaining) else current - activity
            }
        }
    }
}
