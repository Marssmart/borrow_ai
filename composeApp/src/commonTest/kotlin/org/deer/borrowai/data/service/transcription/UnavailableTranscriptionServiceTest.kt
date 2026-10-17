package org.deer.borrowai.data.service.transcription

import arrow.core.Either
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UnavailableTranscriptionServiceTest {
    @Test
    fun reportsEngineUnavailableInsteadOfEmptyText() = runTest {
        val result = UnavailableTranscriptionService.transcribe("recordings/a.m4a")

        assertEquals(Either.Left(TranscriptionError.EngineUnavailable), result)
    }
}
