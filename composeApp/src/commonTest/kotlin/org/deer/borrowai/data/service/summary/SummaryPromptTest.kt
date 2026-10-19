package org.deer.borrowai.data.service.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SummaryPromptTest {
    @Test
    fun parseReturnsSummaryForPlainText() {
        assertEquals(SummaryResult.Summary("Plan for the weekend trip."), SummaryPrompt.parse("Plan for the weekend trip."))
    }

    @Test
    fun parseStripsMarkupAndCollapsesWhitespace() {
        assertEquals(
            SummaryResult.Summary("Notes on the budget review."),
            SummaryPrompt.parse("  Notes on the\n\n`budget`  review.  "),
        )
    }

    @Test
    fun parseReturnsEmptyForBlankReply() {
        assertEquals(SummaryResult.Empty, SummaryPrompt.parse("  \n "))
        assertEquals(SummaryResult.Empty, SummaryPrompt.parse("***"))
    }

    @Test
    fun parseCapsLength() {
        val result = assertIs<SummaryResult.Summary>(SummaryPrompt.parse("word ".repeat(200)))
        assert(result.text.length <= SummaryPrompt.MAX_SUMMARY_CHARS)
    }

    @Test
    fun userMessageTrimsAndCapsTranscription() {
        val message = SummaryPrompt.userMessage("  hello  ")
        assertEquals("Voice note:\nhello", message)
        val long = SummaryPrompt.userMessage("a".repeat(SummaryPrompt.MAX_TRANSCRIPTION_CHARS + 50))
        assertEquals(SummaryPrompt.MAX_TRANSCRIPTION_CHARS + "Voice note:\n".length, long.length)
    }
}
