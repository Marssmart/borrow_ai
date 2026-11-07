package org.deer.borrowai

import kotlin.test.Test
import kotlin.test.assertEquals

class BottomNavBarTest {
    @Test
    fun speakersDestinationIsOnlyVisibleWhenSpeakerRecognitionIsEnabled() {
        assertEquals(
            listOf(BottomNavItem.RECORD, BottomNavItem.MEMORIES, BottomNavItem.SETTINGS),
            visibleBottomNavItems(speakerFeatureEnabled = false),
        )
        assertEquals(
            listOf(
                BottomNavItem.RECORD,
                BottomNavItem.MEMORIES,
                BottomNavItem.SPEAKERS,
                BottomNavItem.SETTINGS,
            ),
            visibleBottomNavItems(speakerFeatureEnabled = true),
        )
    }
}
