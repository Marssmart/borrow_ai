# Architecture

BorrowAI is one Kotlin Multiplatform codebase. Logic, UI, and interfaces live in `commonMain`. Platform code lives in `androidMain` and `iosMain`. The package root is `composeApp/src/*/kotlin/org/deer/borrowai/`.

## Layers

| Layer | Location | Contents |
|-------|----------|----------|
| UI | `commonMain/.../` (for example `App.kt`, `BorrowCaptureScreen.kt`, `SavedMemoriesScreen.kt`) | Compose screens and state holders (`CaptureUiStateHolder`, `SavedMemoriesStateHolder`, `AppShellStateHolder`). |
| Services | `commonMain/.../data/service/` | Interfaces and implementations for memories, recording, speech, summaries, categories, models, and speakers. |
| Domain | `commonMain/.../data/domain/` | Pure data types such as `Memory`, `MemoryDraft`, and `Speaker`. |
| Composition root | `commonMain/.../data/app/AppServices.kt` | Builds the object graph and closes it. |
| Platform | `androidMain/`, `iosMain/` | Recorders, players, microphone permission, device RAM, HTTP clients, and the speech and LLM runtimes on Android. |

## The memory model

A `Memory` (`data/domain/Memory.kt`) has an id, creation time, title, summary, transcription, and an optional `recordingPath`. It also has:

- a `deleted` flag for soft delete;
- a status and attempt counter for each pipeline: transcription, summary, categorization, and speaker analysis;
- a list of category ids;
- the last speaker error and the speaker analysis version.

`recordingPath` is always relative (for example `recordings/<id>.m4a`), never absolute. On iOS the app container path changes between installs, so the platform resolves the path at playback time. `MemoryDraft` is the form before save.

## Persistence

- `KotbaseMemoryService` implements `MemoryService` on Couchbase Lite through Kotbase. The database is named `borrow-ai`.
- `KotbaseSpeakerService` keeps speaker data in its own collection.
- `StatefulMemoryService` is the abstract base for memory state.
- Operations return Arrow `Either<MemoryError, T>`, so callers handle failures as values.

## Recording and playback

- `RecordingMediaSessionService` implements `MediaSessionService` and drives the session states Idle, Recording, and Playing.
- `AudioRecorder` has Android and iOS implementations and writes M4A files named `<id>.m4a`. Unfinished captures use a temporary prefix.
- `LiveTranscriber` shows text while recording, on a 16 kHz path.
- `BatchTranscriber` transcribes the finished file.

## Background jobs

These jobs live in `data/service/`. Each one skips its work while a recording is in progress.

| Job | Location | What it does | Schedule |
|-----|----------|--------------|----------|
| `RecordingReconciler` | `recording/` | Creates memories for orphaned `.m4a` files that have no saved memory. | Every 15 minutes. |
| `DeletedMemoryPurger` | `recording/` | Removes soft-deleted memories, their audio, and their speaker data. | Every 15 minutes. |
| `TranscriptionWorkRunner` | `recording/` | Runs queued transcriptions. A sweep at launch recovers unfinished ones. | Event-driven, with a launch sweep. |
| `MemorySummaryRecoveryJob` | `summary/` | Retries summaries that failed or were never made. | Every 15 minutes. |
| `MemoryCategorizationRecoveryJob` | `category/` | Retries categorization that failed or was never made. | Every 15 minutes. |
| `SpeakerAnalysisRunner` | `speaker/` | Analyzes speakers after a transcription succeeds. Runs only when the speaker feature is enabled. | Event-driven. Off by default. |

The reconciler, purger, speaker runner, and the summary and categorization recovery jobs take an `isRecording` check, so they do not touch files that are being written.

Each unit of work these jobs do is wrapped in `BackgroundActivityTracker.track(...)` (`data/service/background/`). The tracker counts running work per activity, and the top-center pill reads those counts. See [Background activity indicator](features/background-activity-indicator/background-activity-indicator.html).

## Composition root

`AppServices` (`data/app/AppServices.kt`) exposes `create`, which takes the platform dependencies (recorder, player, permission, device, and the speech and LLM runtimes) and wires the memory service, model setup, media session, speaker runner, and the jobs above. The iOS entry point calls it.

- On iOS, `iosMain/.../MainViewController.kt` calls `AppServices.create` with the iOS recorder, player, permission, and device.
- On Android, `AppServicesViewModel` builds the same graph with the Android speech and LLM runtimes.

`close()` stops the media session, waits for pending saves, cancels the jobs, and then closes the database. This order matters: transcription workers can still use the database until they stop.

## Model setup

`ModelCatalog` pins each model by URL, revision, SHA-256, and size. `DownloadingModelSetupService` downloads them through `ModelDownloader` and `PlatformModelHttpClient`. See [Models and privacy](models-and-privacy.html).

## Tests

- `commonTest`: unit tests for shared logic, including the jobs, the model catalog, and speaker clustering and matching.
- `androidUnitTest`: unit tests for the Android speech, LLM, and summarizer code.
- `androidInstrumentedTest`: device tests, including `AppServicesTest` and `KotbaseMemoryServiceTest`.

## Related documents

- [Features](features/index.html): design documents for upcoming features.
- [Platforms](platforms.html): what is wired on each platform today.
