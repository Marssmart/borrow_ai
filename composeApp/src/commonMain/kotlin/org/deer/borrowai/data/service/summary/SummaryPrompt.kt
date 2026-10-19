package org.deer.borrowai.data.service.summary

/** Builds the summarization request and turns the model's reply into a [SummaryResult]. */
object SummaryPrompt {
    const val SYSTEM_INSTRUCTION =
        "You summarize a personal voice note. Reply with one or two plain sentences that say what the note is about. " +
            "Do not add a preamble, lists, headings, or markdown."

    const val MAX_SUMMARY_CHARS = 400
    const val MAX_TRANSCRIPTION_CHARS = 8_000

    private val markup = Regex("[*_`#>]+")
    private val whitespace = Regex("\\s+")

    fun userMessage(transcription: String): String =
        "Voice note:\n" + transcription.trim().take(MAX_TRANSCRIPTION_CHARS)

    fun parse(reply: String): SummaryResult {
        val cleaned = reply.replace(markup, "").replace(whitespace, " ").trim()
        if (cleaned.isEmpty()) return SummaryResult.Empty
        return SummaryResult.Summary(cleaned.take(MAX_SUMMARY_CHARS).trimEnd())
    }
}
