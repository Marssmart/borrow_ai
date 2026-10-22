package org.deer.borrowai.data.service.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MicrophoneDenialTest {
    @Test
    fun grantedIsNeverDenied() {
        assertNull(microphoneDenialAfter(granted = true, showRationale = false))
        assertNull(microphoneDenialAfter(granted = true, showRationale = true))
    }

    @Test
    fun deniedWithRationaleRequiresRationale() {
        assertEquals(
            MicrophoneDenial.RATIONALE_REQUIRED,
            microphoneDenialAfter(granted = false, showRationale = true),
        )
    }

    @Test
    fun deniedWithoutRationaleIsPermanent() {
        assertEquals(
            MicrophoneDenial.PERMANENTLY_DENIED,
            microphoneDenialAfter(granted = false, showRationale = false),
        )
    }
}
