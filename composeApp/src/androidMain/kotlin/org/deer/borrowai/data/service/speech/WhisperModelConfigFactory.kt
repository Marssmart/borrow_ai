package org.deer.borrowai.data.service.speech

import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/** Fixed language for all live and batch transcription. Whisper is not auto-detecting. */
internal const val TRANSCRIPTION_LANGUAGE = "en"

/** Whisper config shared by live and batch recognizers. Always transcribes (never translates). */
internal fun whisperModelConfig(encoder: String, decoder: String): OfflineWhisperModelConfig =
    OfflineWhisperModelConfig(
        encoder = encoder,
        decoder = decoder,
        language = TRANSCRIPTION_LANGUAGE,
        task = "transcribe",
    )
