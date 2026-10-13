# Changelog

Notable changes to BorrowAI are recorded here. Releases follow Semantic Versioning.

## [1.0.5]

- Added a RAM-based model tier choice for voice-to-text and summarization: devices under 5 GiB get Whisper Tiny and Gemma 2B, 5 GiB to under 10 GiB get Whisper Base and Gemma 7B, and 10 GiB or more get Whisper Large v3 and Gemma 9B.
- Added a real model download service that downloads the tier's models from pinned Hugging Face revisions to static files under the app's models folder. Each file is checked against its size and SHA-256 before it is used, interrupted downloads resume with an HTTP Range request or commit an already-complete valid partial file, and later launches use a size/mtime/digest sidecar to skip re-hashing unchanged files. When both verified files are present, setup reaches Ready without artificial waits or step animations.
- The capture and memories pager is available while model setup runs. The microphone remains disabled until Ready and shows transcription or summarization download progress numerically in megabytes. Android setup uses expedited WorkManager work on unmetered Wi-Fi in a dataSync foreground service with a progress notification; users can explicitly allow mobile data for the current download. Only transient network failures retry, up to five total attempts, resuming from the partial file; permanent model and storage errors show concise guidance. Notification permission is requested once on Android 13 and later only for real model setup; denying it still lets the download run. Verified local models become Ready offline without artificial waits. iOS downloads are not background-capable in this release.
- Replaced the single demo/real startup switch with a data-service variant selector, `BORROW_AI_SERVICE_VARIANT`. Each variant sets demo or real mode for memories, media sessions, and model setup; `demo` is the debug default, and unknown or missing values fail fast.
- Demo model setup now reaches Ready immediately, without simulating a download timeline.
- Added the Android run configurations "App - All demo" and "App - Real model setup", replacing "Run App" and "App".
- Updated Android and iOS versioning to 1.0.5 (build 6). Memories, media sessions, transcription, and summarization remain demo-only.

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
