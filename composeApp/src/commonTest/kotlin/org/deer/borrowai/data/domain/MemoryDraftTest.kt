package org.deer.borrowai.data.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MemoryDraftTest {
    @Test
    fun allowsBlankTitleForAutoSavedMemories() {
        val draft = MemoryDraft(title = "", summary = "", transcription = "")
        assertNull(draft.recordingPath)
    }

    @Test
    fun acceptsRelativeRecordingPaths() {
        MemoryDraft("", "", "", recordingPath = "recordings/abc.m4a")
        MemoryDraft("", "", "", recordingPath = "recordings/nested/abc.m4a")
    }

    @Test
    fun rejectsAbsoluteAndUnsafeRecordingPaths() {
        listOf(
            "/var/mobile/Containers/abc.m4a",
            "\\recordings\\abc.m4a",
            "file:///tmp/abc.m4a",
            "C:\\recordings\\abc.m4a",
            "recordings/../secret.m4a",
            "..\\secret.m4a",
            "   ",
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                MemoryDraft("", "", "", recordingPath = path)
            }
        }
    }

    @Test
    fun acceptsUpToThreeDistinctCategoryIds() {
        MemoryDraft("", "", "", categories = emptyList())
        MemoryDraft("", "", "", categories = listOf(1, 2, 3))
    }

    @Test
    fun rejectsInvalidCategoryIds() {
        listOf(
            listOf(1, 2, 3, 4),
            listOf(0),
            listOf(2, 2),
        ).forEach { ids ->
            assertFailsWith<IllegalArgumentException>(ids.toString()) {
                MemoryDraft("", "", "", categories = ids)
            }
        }
    }

    @Test
    fun serializesCategoriesAsPipeSeparatedIds() {
        assertEquals("1|7|12", listOf(1, 7, 12).toCategoriesString())
        assertEquals("", emptyList<Int>().toCategoriesString())
    }

    @Test
    fun parsesPipeSeparatedCategoryIds() {
        assertEquals(listOf(1, 7, 12), parseCategoriesString("1|7|12"))
        assertEquals(emptyList(), parseCategoriesString(""))
        assertEquals(emptyList(), parseCategoriesString(null))
    }

    @Test
    fun parsesEveryStoredCategoryIdEvenOverTheWriteLimit() {
        assertEquals(listOf(1, 2, 3, 4, 5), parseCategoriesString("1|2|3|4|5"))
        assertEquals(
            listOf(1, 2, 3, 4),
            Memory(
                id = "m",
                createdAtEpochMillis = 0L,
                title = "",
                summary = "",
                transcription = "",
                categories = listOf(1, 2, 3, 4),
            ).categories,
        )
    }

    @Test
    fun rejectsMalformedStoredCategories() {
        listOf("1||2", "a|2", "0", "1|1", "-1").forEach { raw ->
            assertFailsWith<IllegalArgumentException>(raw) { parseCategoriesString(raw) }
        }
    }

    @Test
    fun enforcesCategoryMasterRowLimits() {
        Category(id = 1, name = "a".repeat(CATEGORY_NAME_MAX_LENGTH), description = "a".repeat(CATEGORY_DESCRIPTION_MAX_LENGTH))
        assertFailsWith<IllegalArgumentException> {
            Category(id = 1, name = "a".repeat(CATEGORY_NAME_MAX_LENGTH + 1), description = "")
        }
        assertFailsWith<IllegalArgumentException> {
            Category(id = 1, name = " ", description = "")
        }
        assertFailsWith<IllegalArgumentException> {
            Category(id = 1, name = "ok", description = "a".repeat(CATEGORY_DESCRIPTION_MAX_LENGTH + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            Category(id = 0, name = "ok", description = "")
        }
    }
}
