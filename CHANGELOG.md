# Changelog

Notable changes to BorrowAI are recorded here. Releases follow Semantic Versioning.

## [1.0.1]

- Added mock onboarding with three voice-model and three LLM choices, simulated downloads, and a completion gate.
- Added an 80%-width tap/hold microphone screen with a live Lorem Ipsum preview and swipe navigation.
- Added a chronological, expandable saved-memories screen with mock transcript, summary, and playback.
- Added the Compose-required iOS frame-duration plist setting to prevent startup failure.
- Model selection, downloads, recording, transcription, and playback remain simulated.

## [1.0.0]

- Bootstrapped a root-level Kotlin Multiplatform project with shared Compose Multiplatform UI.
- Added Android, iOS device, Apple-silicon simulator, and x86_64 simulator targets.
- Added the shared “Hello from BorrowAI” starter screen and platform launch hosts.
- Verified Android launch on Pixel 10.
- Initial release is a blank starter; dictation, notes, persistence, and product features are not included.
