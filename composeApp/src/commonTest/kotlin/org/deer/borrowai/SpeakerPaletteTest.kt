package org.deer.borrowai

import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakerPaletteTest {
    @Test
    fun paletteColorsAreConvertedFromArgbValues() {
        speakerPalette.forEachIndexed { index, argb ->
            assertEquals(argb.toInt(), speakerPaletteColor(index).toArgb())
        }
    }
}
