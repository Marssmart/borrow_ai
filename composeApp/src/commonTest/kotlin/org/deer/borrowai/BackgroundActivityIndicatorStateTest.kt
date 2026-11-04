package org.deer.borrowai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.background.BackgroundActivity

class BackgroundActivityIndicatorStateTest {
    @Test
    fun hiddenWhenNothingIsRunning() {
        val state = backgroundActivityIndicatorState(activities = emptySet(), showIndicator = true)

        assertFalse(state.visible)
        assertEquals(emptyList(), state.activities)
    }

    @Test
    fun hiddenWhenTheSettingIsOffEvenIfWorkRuns() {
        val state = backgroundActivityIndicatorState(
            activities = setOf(BackgroundActivity.TRANSCRIPTION),
            showIndicator = false,
        )

        assertFalse(state.visible)
        assertEquals(listOf(BackgroundActivity.TRANSCRIPTION), state.activities)
    }

    @Test
    fun visibleWhenTheSettingIsOnAndWorkRuns() {
        val state = backgroundActivityIndicatorState(
            activities = setOf(BackgroundActivity.SUMMARY),
            showIndicator = true,
        )

        assertTrue(state.visible)
    }

    @Test
    fun activitiesFollowTheEnumOrder() {
        val state = backgroundActivityIndicatorState(
            activities = setOf(BackgroundActivity.CLEANUP, BackgroundActivity.MODEL_DOWNLOAD, BackgroundActivity.SUMMARY),
            showIndicator = true,
        )

        assertEquals(
            listOf(BackgroundActivity.MODEL_DOWNLOAD, BackgroundActivity.SUMMARY, BackgroundActivity.CLEANUP),
            state.activities,
        )
    }

    @Test
    fun namesTwoActivitiesAndCountsTheRest() {
        val state = backgroundActivityIndicatorState(
            activities = BackgroundActivity.entries.toSet(),
            showIndicator = true,
        )

        assertEquals(
            listOf(BackgroundActivity.MODEL_DOWNLOAD, BackgroundActivity.TRANSCRIPTION),
            state.namedActivities,
        )
        assertEquals(4, state.hiddenCount)
    }

    @Test
    fun noHiddenCountWhenEveryActivityIsNamed() {
        val state = backgroundActivityIndicatorState(
            activities = setOf(BackgroundActivity.SUMMARY),
            showIndicator = true,
        )

        assertEquals(0, state.hiddenCount)
        assertEquals(listOf(BackgroundActivity.SUMMARY), state.namedActivities)
    }

    @Test
    fun transcriptionProgressIsIncludedOnlyForActiveTranscriptionAndNeverReachesOneHundred() {
        val state = backgroundActivityIndicatorState(
            activities = setOf(BackgroundActivity.TRANSCRIPTION),
            showIndicator = true,
            transcriptionProgressPercent = 54,
        )
        val completed = backgroundActivityIndicatorState(
            activities = setOf(BackgroundActivity.TRANSCRIPTION),
            showIndicator = true,
            transcriptionProgressPercent = 100,
        )
        val unrelated = backgroundActivityIndicatorState(
            activities = setOf(BackgroundActivity.SUMMARY),
            showIndicator = true,
            transcriptionProgressPercent = 54,
        )

        assertEquals(54, state.transcriptionProgressPercent)
        assertNull(completed.transcriptionProgressPercent)
        assertNull(unrelated.transcriptionProgressPercent)
    }
}
