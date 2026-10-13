package org.deer.borrowai.data.service.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelDownloadFormatTest {
    @Test
    fun formatsWholeMegabytesWithThousandsSeparators() {
        assertEquals("0", ModelDownloadFormat.megabytes(0L))
        assertEquals("0", ModelDownloadFormat.megabytes(999_999L))
        assertEquals("1", ModelDownloadFormat.megabytes(1_000_000L))
        assertEquals("77", ModelDownloadFormat.megabytes(77_691_713L))
        assertEquals("1,708", ModelDownloadFormat.megabytes(1_708_582_752L))
        assertEquals("3,095", ModelDownloadFormat.megabytes(3_095_033_483L))
        assertEquals("123,456", ModelDownloadFormat.megabytes(123_456_789_012L))
    }

    @Test
    fun rejectsNegativeByteCounts() {
        assertFailsWith<IllegalArgumentException> { ModelDownloadFormat.megabytes(-1L) }
    }
}
