# Changelog

Notable changes to BorrowAI are recorded here. Releases follow Semantic Versioning.

## [1.0.6]

- Added RAM-based model tiers for transcription and summarization, selected from device-reported memory and mapped to pinned model catalog entries.
- Added verified model downloads to static app-private files, with SHA-256 and expected-size checks, resumable `.part` files bound to their model identity, and cached verification sidecars. Oversized responses are stopped before they can exceed the catalog size; short downloads remain resumable.
- Added Android foreground WorkManager downloads with progress notifications, unmetered-network defaults, an explicit per-download mobile-data override, retry/backoff state, and notification tap-through to the saved app variant.
- Moved setup status and numeric MB progress to the capture screen; recording remains disabled until models are ready. Cached models become ready without artificial waits. Model setup always uses the real download service, so every variant downloads real models.
- Persisted authenticated data-service variants. Debug run-config extras work through the trusted shell launch path; exported callers cannot force or persist real setup, and notification launches restore the saved variant.
- Updated Android and iOS app versions to 1.0.6 (build 7).

## [1.0.5]

- Removed the standalone model-loading route and moved setup status and progress onto the capture screen and recording button.
- Simulated model selection, voice-model download and loading, and summarization-model download in the demo service so setup visuals are shown when the app launches.
- Updated Android and iOS versioning to 1.0.5 (build 6). Model setup, recording, transcription, and summarization remain simulated.

## [1.0.4]

- Added a basic GitHub Actions Gradle job that runs Android unit tests and assembles the debug app.
- Bumped Android and iOS app versions to 1.0.4 (build 5).

## [1.0.3]

- Added model setup, memory CRUD, and media-session service contracts with in-memory demo providers and service tests.
- Moved app, capture, model-loading, and saved-memory UI state into `StateFlow` holders, keeping pager/scroll state and component constants with their owners.
- Localized app, capture, model, memory, accessibility, and confirmation copy; added locale-aware Android/iOS memory timestamps and a demo-only data badge.
- Added explicit demo startup wiring for Android and iOS, including a demo default for Android debug builds launched from the home screen. Real mode remains fail-fast.
- Added recording/playback exclusivity and confirmation when playback replaces a recording.
- Retained the service graph across Android configuration changes and the iOS controller lifetime; closed background work with its owner and guarded restarted sessions from stale updates.
- Made the memory audio glyph and accessibility description reflect active transcription or summary playback.
- Updated Android and iOS versioning to 1.0.3 (build 4). Transcription, summarization, and persistent storage remain demo-only; production ML and ObjectBox providers are not implemented.

## [1.0.2]

- Smoothed model-loading progress and microphone pulse transitions; the mic retains a circular outline without elevation artifacts.
- Removed memory-card elevation artifacts and detail-card shadows from the title and playback panels; summary and transcription panels retain their glass shadows.
- Removed redundant playback captions; the play/pause controls alone indicate playback state.
- Verified the pulse on Pixel 10. Android's global Animator duration scale must be nonzero for Compose animations; it was set to 1× on the test device.
- Model setup, recording, transcription, and playback remain simulated.

## [1.0.1]

- Replaced model selection with simulated download/load steps for voice-to-text and summarization, connected by a vertical progress timeline.
- Applied Aurora Glass gradients, a frosted loading timeline, aligned memory/detail panels, and high-contrast text while preserving the pulsing 80%-width tap/hold recorder and live transcript.
- Smoothed loading-step transitions and strengthened the recording pulse; removed Android elevation artifacts from memory and detail title/playback cards, with playback state shown on controls.
- Removed the recording button's elevated shadow on Android to preserve its circular outline.
- Added newest-first memories with 240-character previews; tapping a card opens scrollable full-text detail with mock audio controls.
- Added nested detail → list → capture swiping; the memory list is unbranded, and loading shows a centered BorrowAI title.
- Added the Compose-required iOS frame-duration plist setting; model setup, recording, transcription, and playback remain simulated.

## [1.0.0]

- Bootstrapped a root-level Kotlin Multiplatform project with shared Compose Multiplatform UI.
- Added Android, iOS device, Apple-silicon simulator, and x86_64 simulator targets.
- Added the shared “Hello from BorrowAI” starter screen and platform launch hosts.
- Verified Android launch on Pixel 10.
- Initial release is a blank starter; dictation, notes, persistence, and product features are not included.
