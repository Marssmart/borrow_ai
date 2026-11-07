package org.deer.borrowai

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeakerCardBringIntoViewTest {
    @Test
    fun bringIntoViewBoundsDoNotExpandWithoutAShadow() {
        val bounds = speakerCardBringIntoViewRect(IntSize(width = 100, height = 50), shadowOutsetPx = 0f)
        assertTrue(bounds.left == 0f && bounds.top == 0f)
        assertEquals(100f, bounds.right)
        assertEquals(50f, bounds.bottom)
    }

    @Test
    fun bringIntoViewBoundsIncludePositiveShadowOutset() {
        assertEquals(
            Rect(left = -12f, top = -12f, right = 112f, bottom = 62f),
            speakerCardBringIntoViewRect(IntSize(width = 100, height = 50), shadowOutsetPx = 12f),
        )
    }
}
