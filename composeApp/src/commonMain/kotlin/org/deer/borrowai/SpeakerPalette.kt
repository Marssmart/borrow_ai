package org.deer.borrowai

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * The speaker palette (section 9.7) as fill colours in ARGB. A speaker's stored colour is an index into this list. Each fill
 * passes WCAG AA (4.5:1) with white text.
 */
internal val speakerPalette: List<Long> = listOf(
    0xFF14757B,
    0xFF4338CA,
    0xFF854D0E,
    0xFFBE123C,
    0xFF7E22CE,
    0xFF15803D,
    0xFF0369A1,
    0xFF9A3412,
)

internal fun speakerPaletteColor(colorIndex: Int): Color = Color(speakerPalette[colorIndex])

/** The WCAG contrast ratio of the opaque colour [argb] against white. */
internal fun contrastWithWhite(argb: Long): Double {
    fun linear(shift: Int): Double {
        val channel = ((argb shr shift) and 0xFF) / 255.0
        return if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }
    val luminance = 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    return 1.05 / (luminance + 0.05)
}
