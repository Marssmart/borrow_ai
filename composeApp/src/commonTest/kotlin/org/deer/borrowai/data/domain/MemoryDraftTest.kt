package org.deer.borrowai.data.domain

import kotlin.test.Test
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
}
