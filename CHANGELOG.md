# Changelog

Notable changes to BorrowAI are recorded here. Releases follow Semantic Versioning.

## [1.0.1]

- Replaced model selection with simulated download/load steps for voice-to-text and summarization, connected by a vertical progress timeline.
- Refined capture with an 80%-width tap/hold mic, pulsing white/red recording state, live-typing transcript, slide transitions, and the off-white/green palette.
- Added newest-first memories with 240-character previews; tapping a card opens scrollable full-text detail with mock audio controls.
- Added nested detail → list → capture swiping; the memory list is unbranded, and loading shows a centered BorrowAI title.
- Added the Compose-required iOS frame-duration plist setting; model setup, recording, transcription, and playback remain simulated.

## [1.0.0]

- Bootstrapped a root-level Kotlin Multiplatform project with shared Compose Multiplatform UI.
- Added Android, iOS device, Apple-silicon simulator, and x86_64 simulator targets.
- Added the shared “Hello from BorrowAI” starter screen and platform launch hosts.
- Verified Android launch on Pixel 10.
- Initial release is a blank starter; dictation, notes, persistence, and product features are not included.
