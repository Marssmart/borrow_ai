package org.deer.borrowai.data.service.background

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupState

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
    private val mutableTranscriptionProgress = MutableStateFlow<Int?>(null)

    /** The activities with at least one unit of work running. Emits only when that set changes. */
    val activities: Flow<Set<BackgroundActivity>> = counts
        .map { it.keys.toSet() }
        .distinctUntilChanged()

    /** Coarse transcription work completed, or null when progress is unavailable or inactive. */
    val transcriptionProgress: StateFlow<Int?> = mutableTranscriptionProgress.asStateFlow()

    /** Marks [activity] active while [block] runs. The mark is removed when [block] returns, throws, or is cancelled. */
    suspend fun <T> track(activity: BackgroundActivity, block: suspend () -> T): T {
        if (activity == BackgroundActivity.TRANSCRIPTION && counts.value[activity] == null) {
            mutableTranscriptionProgress.value = null
        }
        counts.update { current ->
            val activeCount = current[activity] ?: 0
            current + (activity to activeCount + 1)
        }
        try {
            return block()
        } finally {
            counts.update { current ->
                val remaining = (current[activity] ?: 1) - 1
                if (remaining > 0) {
                    current + (activity to remaining)
                } else {
                    if (activity == BackgroundActivity.TRANSCRIPTION) mutableTranscriptionProgress.value = null
                    current - activity
                }
            }
        }
    }

    /** Updates the active transcription's coarse percentage; 100 is intentionally not a reportable value. */
    fun reportTranscriptionProgress(percent: Int) {
        require(percent in 1..99) { "Transcription progress must be between 1 and 99." }
        mutableTranscriptionProgress.value = percent
    }
}

/** Model download is not tracked by the jobs. It comes from the model setup phase instead. */
internal fun activeBackgroundActivities(
    tracked: Set<BackgroundActivity>,
    model: ModelSetupState,
): Set<BackgroundActivity> =
    if (model.phase in MODEL_WORK_PHASES) tracked + BackgroundActivity.MODEL_DOWNLOAD else tracked

private val MODEL_WORK_PHASES = setOf(
    ModelSetupPhase.DOWNLOADING_LIVE_VOICE,
    ModelSetupPhase.DOWNLOADING_VOICE,
    ModelSetupPhase.LOADING_VOICE,
    ModelSetupPhase.DOWNLOADING_LLM,
)
