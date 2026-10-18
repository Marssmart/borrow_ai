package org.deer.borrowai.data.service.recording

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveAudioWindowTest {
    private fun LiveAudioWindow.snapshotPcm(): List<Int> = snapshot().map { (it * 32768f).roundToInt() }

    @Test
    fun emptyWindowHasNoSamples() {
        val window = LiveAudioWindow(4)

        assertEquals(0, window.size)
        assertEquals(emptyList(), window.snapshotPcm())
    }

    @Test
    fun keepsChronologicalOrderBeforeWrap() {
        val window = LiveAudioWindow(4)

        window.appendPcm16(shortArrayOf(1, 2, 3))

        assertEquals(3, window.size)
        assertEquals(listOf(1, 2, 3), window.snapshotPcm())
    }

    @Test
    fun keepsOnlyMostRecentSamplesAfterWrap() {
        val window = LiveAudioWindow(3)

        window.appendPcm16(shortArrayOf(1, 2, 3, 4, 5))

        assertEquals(3, window.size)
        assertEquals(listOf(3, 4, 5), window.snapshotPcm())
    }

    @Test
    fun appendsOnlyRequestedCount() {
        val window = LiveAudioWindow(4)

        window.appendPcm16(shortArrayOf(7, 8, 9), count = 2)

        assertEquals(listOf(7, 8), window.snapshotPcm())
    }

    @Test
    fun clearResetsWindow() {
        val window = LiveAudioWindow(3)
        window.appendPcm16(shortArrayOf(1, 2, 3, 4))

        window.clear()

        assertEquals(0, window.size)
        assertEquals(emptyList(), window.snapshotPcm())
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { LiveAudioWindow(0) }
    }
}
