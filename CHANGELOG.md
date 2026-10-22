# Changelog

Notable changes to BorrowAI are recorded here. Releases follow Semantic Versioning.

## [Unreleased]

## [1.4.4]

- Show guidance on the capture screen when microphone permission is denied. A rationale with an "Allow" button appears when Android says a rationale should be shown. An "Open Settings" button appears when the permission is permanently denied.
- Re-check microphone permission on resume, so the guidance clears after the user grants access in Settings.
- Updated Android and iOS versioning to 1.4.4 (build 35).

## [1.4.3]

- Fixed `AppServices.close()` skipping `awaitPendingWork()` when `mediaSession.close()` threw. A save started before close could then race the database close. Each close step now runs even when an earlier step throws, and the close order is unchanged: media session, pending saves, jobs, database.
- Updated Android and iOS versioning to 1.4.3 (build 34).

## [1.4.2]

- Simplified memory summary tracking to use `SummaryStatus` as the single source of truth. The redundant stored `summarized` boolean was removed. `summarized` is now a computed property derived from `summaryStatus`, so existing callers still compile and the value cannot drift.
- Updated Android and iOS versioning to 1.4.2 (build 33).

## [1.4.1]

- Added a `native-developer` custom agent in `.github/agents/`. It implements native Android and iOS changes using current platform practices. It plans first, checks the iOS deployment target and minSdk before using newer APIs, and follows the MR and version rules in `AGENTS.md`. It runs a review loop with `native-reviewer` (3 rounds maximum).
- Updated Android and iOS versioning to 1.4.1 (build 32).

## [1.4.0]

- Added a WorkManager-based transcription recovery system on Android. Memories are saved as `PENDING` and transcribed in the background, instead of inline after recording. A startup sweep requeues memories that were missing or unfinished transcripts.
- Replaced the transcription state with `transcriptionStatus` (`PENDING`, `IN_PROGRESS`, `DONE`, `FAILED`) and `transcriptionAttempts`, capped at 5 attempts. A missing model does not use up an attempt.
- Failed transcription attempts now record the reason in `lastTranscriptionError`. A failed write makes the drain job retry instead of reporting success.
- Updated Android and iOS versioning to 1.4.0 (build 31).

## [1.3.3]

- Added a `native-simplifier` custom agent in `.github/agents/`. It reviews native Android and iOS code for hand-rolled plumbing that platform APIs already cover, and proposes replacements.
- Updated Android and iOS versioning to 1.3.3 (build 30).

## [1.3.1]

- Removed the "Play original" button from saved memories. Each memory now shows only "Play recording" and "Play summary" (when a summary exists). The design guide was updated to match.
- Updated Android and iOS versioning to 1.3.1 (build 28).

## [1.3.0]

- Added an on-device memory summarizer on Android, built on LiteRT-LM (Gemma model). Each memory gets one short summary, produced after transcription and retried by the recovery job.
- Summaries are stored per memory. iOS keeps the no-op summarizer for now, so iOS memories get no summaries yet.
- The download progress ring around the record button now also shows for the transcription and summary model downloads, not only the live voice download.
- Updated Android and iOS versioning to 1.3.0 (build 27).

## [1.2.0]

- The memory summary recovery job also runs right after a recording's transcription finishes, not only at app start and every 15 minutes.
- Memories whose transcript is still pending are retried. Blank transcripts from a finished transcription give up at once, which also applies on iOS.
- Each memory now has a summary status (pending, summarized, empty, or gave up) and stores the last transcription error.
- The summarizer engine is not wired yet, so the job currently stores no summaries. No UI changes.
- Updated Android and iOS versioning to 1.2.0 (build 26).

## [1.1.0]

- Added a memory summary recovery job. It runs at app start and every 15 minutes, and retries memories that were saved without a summary. Each memory gets up to 10 attempts. Empty or untranscribable results give up immediately, so they are not retried forever. Existing memories are migrated to the new summary state on first launch.
- The summarizer engine is not wired yet, so the job currently stores no summaries. No UI changes.
- Updated Android and iOS versioning to 1.1.0 (build 25).

## [1.0.23]

- Untranscribed memories now look distinct in the saved list: a cream card with a tan border and a muted "Waiting for transcription…" placeholder. They show "Play original" (plays the recording) and no "Play summary".
- Updated the design guide with the pending memory card style.
- Updated Android and iOS versioning to 1.0.23 (build 24).

## [1.0.22]

- Added `AGENTS.md` with contributor rules (every MR must update the changelog and bump the version) and a summary of the app's architecture and core concepts for AI agents.
- Updated Android and iOS versioning to 1.0.22 (build 23).

## [1.0.21]

- Live transcription on Android is merged with the hardened transcriber from 1.0.20. Its capture and decode behaviour is unchanged from 1.0.18.
- Updated Android and iOS versioning to 1.0.21 (build 22).

## [1.0.20]

- Finished recordings are transcribed in batch on Android with the Whisper model for the device tier. Batch transcription runs after a recording is saved and does not affect the live transcript.
- M4A recordings are decoded to 16 kHz mono before batch transcription. Long recordings are processed in 30 s windows.
- Updated Android and iOS versioning to 1.0.20 (build 21).

## [1.0.19]

- Fixed model downloads failing on flaky mobile networks. Android model downloads now allow longer socket stalls (60 s) and connect timeouts (30 s) instead of OkHttp's short default read timeout.
- Model download network failures now include the underlying exception type and message in the logged error, so future failures can be diagnosed.

## [1.0.18]

- Added live transcription shown above the record button while recording on Android. It uses the Whisper tiny model, decodes a rolling window about once per second, and is cleared when recording stops.
- Live transcription runs on a separate 16 kHz mono capture path. The saved recording format (M4A) is unchanged.
- Live transcription is skipped when the Whisper tiny model is missing. Recording continues normally, and live transcription failures are logged and do not stop recording.
- Updated Android and iOS versioning to 1.0.18 (build 19).

## [1.0.17]

- The service layer now models failures and responses with Arrow `Either` (Left = failure, Right = success).
- `ModelDownloader`, `ModelFileStore`, and `ModelSetupService.prepare` return `Either<ModelDownloadError, _>`. Cancellation still propagates as an exception.
- `MediaSessionService` playback returns `Either<PlaybackError, PlaybackRequest>`. `ConfirmationRequired` is a normal step, so it is on the Right side.
- `MemoryService` returns `Either<MemoryError, _>` for lookups, listing, create, update, and delete. A malformed row is reported as a Left instead of being dropped silently.
- Updated Android and iOS versioning to 1.0.17 (build 18).

## [1.0.16]

- Removed demo mode: the `BORROW_AI_DATA_MODE` flag, `AppMode`, the demo services, the demo badge, and their tests.
- The app graph now always uses the real services. `DownloadingModelSetupService` is wired with a per-platform `ModelDevice` (RAM and network policy) and models stored under the app root.
- Added `ACCESS_NETWORK_STATE` on Android.
- Updated Android and iOS versioning to 1.0.16 (build 17).

## [1.0.15]

- Added real playback of the raw recording for each saved memory. The Play recording button is always shown; Transcription and Summary buttons appear only when that text exists.
- Added `AudioPlayer` with Android `MediaPlayer` and iOS `AVAudioPlayer` implementations. Playback runs through `MediaSessionService`, and a playing recording stops when the service closes.
- Playing a recording while a recording is in progress asks for confirmation. Confirming saves the active recording first, so audio is not lost.
- Missing or unreadable recordings show a "recording unavailable" message instead of failing silently.
- Updated Android and iOS versioning to 1.0.15 (build 16).

## [1.0.14]

- Added `RecordingMediaSessionService`, which records microphone audio to `recordings/<id>.m4a` under the app's files directory on Android and iOS.
- Recording requests microphone permission first. Stopping before the grant arrives cancels the pending start, and a denied grant leaves the service idle.
- Stopping a recording auto-saves a memory with a blank title, summary, and transcription, and a relative `recordingPath`. A failed recording deletes its partial file and saves nothing.
- The REAL app graph now uses `KotbaseMemoryService` and the recording service. Demo mode is unchanged.
- Added `RECORD_AUDIO` on Android and `NSMicrophoneUsageDescription` on iOS.
- Updated Android and iOS versioning to 1.0.14 (build 15).

## [1.0.13]

- Added `KotbaseMemoryService`, a `MemoryService` backed by Kotbase (Couchbase Lite) with create, read, update, delete, and list operations.
- Added Kotbase and AndroidX test dependencies, plus device tests for the Kotbase CRUD operations.
- Added abstract `StatefulMemoryService`, which owns a protected `MutableStateFlow<List<Memory>>` and exposes it read-only through `getState(): StateFlow<List<Memory>>`. `KotbaseMemoryService` extends it. `MemoryService` no longer exposes a mutable `memories` field, and `InMemoryDemoMemoryService` implements `MemoryService` directly with a private flow and the same `getState()`. The separate `mutableMemories` backing field is removed.
- Updated Android and iOS versioning to 1.0.13 (build 14).

## [1.0.12]

- Added `recordingPath` to `Memory` and `MemoryDraft` so a memory can point at its raw recording on device storage.
- `recordingPath` must be a relative path under the app's recordings root. Absolute paths and paths that escape the root are rejected, so stored memories survive app updates and container moves on both Android and iOS.
- A memory no longer needs a title. Blank titles are accepted.
- `InMemoryDemoMemoryService` stores and returns `recordingPath`.
- Updated Android and iOS versioning to 1.0.12 (build 13).

## [1.0.11]

- `CaptureUiStateHolder` now updates its UI state with `MutableStateFlow.update` in `stopRecording` and `bindMediaSession` instead of reading and reassigning `.value`, so concurrent updates are no longer lost.
- Added `MutableCaptureUiState`, a wrapper around the capture UI state that owns `UiState`, with `isRecording`, `isHoldRecording`, and `update` helpers. `CaptureUiStateHolder.uiState` now uses it in place of the private `mutableUiState`.
- Updated Android and iOS versioning to 1.0.11 (build 12).

## [1.0.10]

- Added `MutableModelSetupState`, a wrapper around the model setup state with `transition` and `fail` helpers. `DownloadingModelSetupService` and `DemoModelSetupService` now use it instead of copying state by hand.
- `ModelSetupService.state` is now a `MutableModelSetupState`. Flow collectors read `state.state`, and the current value is read through `state.value`.
- Added `isReady` to `ModelSetupState` and `MutableModelSetupState`, replacing `phase == ModelSetupPhase.READY` checks.
- Updated Android and iOS versioning to 1.0.10 (build 11).

## [1.0.9]

- `DownloadingModelSetupService` now uses `MutableJob` for its setup download, removing manual job tracking. `MutableJob` gained `isActive()` and `join()` so `prepare()` can join a running download instead of starting another.
- Updated Android and iOS versioning to 1.0.9 (build 10).

## [1.0.8]

- Added `MutableMediaSessionState`, a wrapper around the media session state with `transitionIdle`, `transitionRecording`, and `transitionPlaying` helpers, plus `ifRecording` and `ifPlaying` to run code only in a given state. `DemoMediaSessionService` now uses it instead of raw state checks and copies.
- Added `MutableSessionGeneration`, a wrapper around the session generation counter with `isCurrent`, `safeSet`, and `advance` helpers. `DemoMediaSessionService` uses it instead of raw `StateFlow` comparisons.
- `ifRecording` takes a `before` step that runs before the recording is read, so `stopRecording` cancels jobs and advances the generation before capturing the final transcription.
- Moved playable text selection into `PlaybackTarget.getText(memory)`.
- Updated Android and iOS versioning to 1.0.8 (build 9).

## [1.0.7]

- Added `MutableJob`, a single-job wrapper that takes the coroutine scope in its constructor, starts jobs with `launch()`, and clears its reference with `cancel()`. `DemoMediaSessionService` uses it for ticker and transcription jobs, removing manual cancel/null/launch boilerplate.
- Updated Android and iOS versioning to 1.0.7 (build 8).

## [1.0.6]

- Added the real tier-based model download service for Whisper and Gemma models, with resumable HTTP transfers, pinned revisions, SHA-256 verification, and persistent verified storage.
- Updated Android and iOS versioning to 1.0.6 (build 7). App startup still uses the demo service until real-service wiring is added.

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
