package org.deer.borrowai.data.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupState

class SummaryModelMissingNoticeTest {
    @Test
    fun noticeClearsWhenSetupBecomesReadyBeforeNextRecovery() = runTest {
        val missing = MutableStateFlow(true)
        val setup = MutableStateFlow(ModelSetupState(phase = ModelSetupPhase.DOWNLOADING_LLM))
        val notice = summaryModelMissingNotice(missing, setup)

        assertTrue(notice.first())

        setup.value = ModelSetupState(phase = ModelSetupPhase.READY)

        // The recovery job only refreshes its flag on its next run, so the notice must not depend on it.
        assertTrue(missing.value)
        assertFalse(notice.first())
    }

    @Test
    fun noticeFollowsRecoveryFlagWhileSetupIsNotReady() = runTest {
        val missing = MutableStateFlow(false)
        val setup = MutableStateFlow(ModelSetupState(phase = ModelSetupPhase.NOT_STARTED))
        val notice = summaryModelMissingNotice(missing, setup)

        assertFalse(notice.first())

        missing.value = true
        assertTrue(notice.first())
    }
}
