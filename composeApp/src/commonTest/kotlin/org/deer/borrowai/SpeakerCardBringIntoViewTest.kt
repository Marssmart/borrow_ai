package org.deer.borrowai

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakerCardBringIntoViewTest {
    @Test
    fun bringIntoViewBoundsIncludeTheCardShadowOutset() {
        assertEquals(
            Rect(left = -12f, top = -12f, right = 112f, bottom = 62f),
            speakerCardBringIntoViewRect(IntSize(width = 100, height = 50), shadowOutsetPx = 12f),
        )
    }
}
