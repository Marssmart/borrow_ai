# Platforms

The app shares its logic across platforms, but not every service is wired on every platform yet. This page lists what the code connects in this build. Check it against the source before relying on it.

| Capability | Android | iOS |
|------------|---------|-----|
| Recording (M4A) | Yes (`AndroidAudioRecorder`) | Yes (`IosAudioRecorder`) |
| Playback | Yes (`AndroidAudioPlayer`) | Yes (`IosAudioPlayer`) |
| Pause, seek, and skip | Yes (`AndroidAudioPlayer`) | No. iOS has play and stop only. |
| Microphone permission | Yes | Yes (`IosMicrophonePermission`) |
| Model downloads | Yes (`PlatformModelHttpClient.android.kt`) | Yes (`PlatformModelHttpClient.ios.kt`) |
| Live transcription | Yes (`AndroidLiveTranscriber`, sherpa-onnx) | Not wired |
| Batch transcription | Yes (`LazyBatchTranscriber`) | Not wired |
| Summaries (LLM) | Yes (`AndroidSummarizer`) | Not wired |
| Categorization (LLM) | Yes (`AndroidCategoryModel`) | Not wired |
| Speaker analysis | Off by default; no speech model is bundled yet | Not wired |

## Notes

- GPU acceleration applies to the Android LLM only (LiteRT-LM `Backend.GPU()`). Speech uses sherpa-onnx on the CPU.
- On iOS, `MainViewController` calls `AppServices.create` with the recorder, player, permission, and device only. The transcriber, summarizer, and category model are not passed, so their defaults apply (none). The speech and LLM runtimes exist only under `androidMain`.
- Speaker recognition is described in the [feature pages](features/index.html). The data layer shipped in version 1.8.0 with no user interface. The read-only speaker screens (list chips, speaker cards, and Settings rows) are on Android only, and no analysis runs until a speech model is pinned.
- Platform checks in this table reflect the source tree at the time these pages were built.
