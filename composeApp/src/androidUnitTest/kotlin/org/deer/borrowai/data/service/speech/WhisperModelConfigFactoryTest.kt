package org.deer.borrowai.data.service.speech

import kotlin.test.Test
import kotlin.test.assertEquals

class WhisperModelConfigFactoryTest {

    @Test
    fun transcriptionLanguageIsEnglish() {
        assertEquals("en", TRANSCRIPTION_LANGUAGE)
    }

    @Test
    fun whisperConfigIsFixedToEnglishAndTranscribes() {
        val config = whisperModelConfig(encoder = "enc.onnx", decoder = "dec.onnx")

        assertEquals("enc.onnx", config.encoder)
        assertEquals("dec.onnx", config.decoder)
        assertEquals("en", config.language)
        assertEquals("transcribe", config.task)
    }
}
