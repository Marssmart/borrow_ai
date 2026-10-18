package org.deer.borrowai.data.service.summarization

import arrow.core.Either
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UnavailableSummarizationServiceTest {
    @Test
    fun returnsPlaceholderSummary() = runTest {
        val result = UnavailableSummarizationService.summarize("some transcription")

        assertEquals(Either.Right("Summarization unavailable"), result)
    }
}
