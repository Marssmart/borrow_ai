package org.deer.borrowai.data.service.background

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupState

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundActivityTrackerTest {
    @Test
    fun activityIsActiveOnlyWhileTheBlockRuns() = runTest {
        val tracker = BackgroundActivityTracker()

        assertEquals(emptySet(), tracker.activities.first())
        val during = tracker.track(BackgroundActivity.SUMMARY) {
            tracker.activities.first()
        }
        assertEquals(setOf(BackgroundActivity.SUMMARY), during)
        assertEquals(emptySet(), tracker.activities.first())
    }

    @Test
    fun overlappingUnitsKeepTheActivityUntilTheLastEnds() = runTest {
        val tracker = BackgroundActivityTracker()

        val duringNested = tracker.track(BackgroundActivity.TRANSCRIPTION) {
            tracker.track(BackgroundActivity.TRANSCRIPTION) { }
            tracker.activities.first()
        }

        assertEquals(setOf(BackgroundActivity.TRANSCRIPTION), duringNested)
        assertEquals(emptySet(), tracker.activities.first())
    }

    @Test
    fun activityIsRemovedWhenTheBlockThrows() = runTest {
        val tracker = BackgroundActivityTracker()

        assertFailsWith<IllegalStateException> {
            tracker.track(BackgroundActivity.CLEANUP) { throw IllegalStateException("boom") }
        }

        assertEquals(emptySet(), tracker.activities.first())
    }

    @Test
    fun independentActivitiesAreReportedTogether() = runTest {
        val tracker = BackgroundActivityTracker()

        val both = tracker.track(BackgroundActivity.SUMMARY) {
            tracker.track(BackgroundActivity.CATEGORIZATION) { tracker.activities.first() }
        }

        assertEquals(setOf(BackgroundActivity.SUMMARY, BackgroundActivity.CATEGORIZATION), both)
    }

    @Test
    fun modelPhaseAddsModelDownloadOnlyWhileWorkRuns() {
        val tracked = setOf(BackgroundActivity.TRANSCRIPTION)

        assertEquals(
            setOf(BackgroundActivity.TRANSCRIPTION, BackgroundActivity.MODEL_DOWNLOAD),
            activeBackgroundActivities(tracked, ModelSetupState(phase = ModelSetupPhase.DOWNLOADING_VOICE)),
        )
        assertEquals(
            setOf(BackgroundActivity.MODEL_DOWNLOAD),
            activeBackgroundActivities(emptySet(), ModelSetupState(phase = ModelSetupPhase.LOADING_VOICE)),
        )
        assertEquals(tracked, activeBackgroundActivities(tracked, ModelSetupState(phase = ModelSetupPhase.READY)))
        assertEquals(
            tracked,
            activeBackgroundActivities(tracked, ModelSetupState(phase = ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK)),
        )
    }
}
