package org.deer.borrowai.data.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutableRunConsentTest {
    @Test
    fun startsRevoked() {
        assertFalse(MutableRunConsent().granted.value)
    }

    @Test
    fun grantAndResetToggleConsent() {
        val consent = MutableRunConsent()

        consent.grant()
        assertTrue(consent.granted.value)

        consent.reset()
        assertFalse(consent.granted.value)
    }
}
