# AGENTS.md

Guidance for AI agents and contributors working in this repository.

## Pull requests

- Every merge request (pull request) targeting this repo MUST include:
  1. A changelog update in `CHANGELOG.md`, under a new version section (or `[Unreleased]` if no bump is being made, which is not allowed for MRs).
  2. A version bump.

- Version bump locations (keep them in sync):
  - `gradle.properties`: `app.version`
  - `composeApp/build.gradle.kts`: `versionCode` (Android build number, increment by 1)
  - `iosApp/iosApp.xcodeproj/project.pbxproj`: `MARKETING_VERSION` (version) and `CURRENT_PROJECT_VERSION` (build number, increment by 1), in both Debug and Release configurations.

- Follow the existing changelog format: `## [x.y.z]` heading with bullet points, newest first, and a note that Android and iOS versioning was updated to the new version and build number.
- Use Semantic Versioning.

## Architecture and concepts

BorrowAI is a Kotlin Multiplatform (Compose) app for Android and iOS. The user records voice notes ("memories"). The app transcribes them on-device and stores them locally. Package root: `composeApp/src/*/kotlin/org/deer/borrowai/`.

- **Memory** (`data/domain/Memory.kt`): id, createdAt, title, summary, transcription, optional `recordingPath`, and a `deleted` soft-delete flag. `MemoryDraft` is the pre-save form. `recordingPath` is always relative, never absolute, because the iOS container path changes between launches.
- **Persistence**: `KotbaseMemoryService` (Couchbase Lite via Kotbase, database `borrow-ai`) implements `MemoryService`. `StatefulMemoryService` is the abstract base class for memory state. Operations return Arrow `Either<MemoryError, T>`.
- **Recording**: `RecordingMediaSessionService` implements `MediaSessionService` and drives `MediaSessionState` (Idle, Recording, Playing). `AudioRecorder` has Android and iOS implementations and outputs M4A. Files are named `<id>.m4a`. Unfinished captures use a temporary prefix.
- **Transcription**: `LiveTranscriber` shows text while recording on a 16 kHz path. `BatchTranscriber` transcribes the finished file. Android implementations live in `androidMain/.../data/service/speech/` and use sherpa-onnx. No iOS transcriber implementation was found under `iosMain`, so verify before assuming iOS transcription works.
- **Background jobs** (`data/service/recording/`): `RecordingReconciler` recreates memories for orphaned `.m4a` files. `DeletedMemoryPurger` removes soft-deleted memories and their audio. Both skip work while recording.
- **Composition root**: `data/app/AppServices.kt` (`create` / `createReal`) wires memory, model setup, media session, reconciler, and purger. iOS builds it in `iosMain/MainViewController.kt`, and Android in `AppServicesViewModel`. `close()` stops the media session, waits for pending saves, cancels jobs, then closes the DB.
- **Models** (`data/service/model/`): `ModelCatalog` pins each model by URL, revision, SHA-256, and size. `ModelTierSelector` picks a tier from device RAM: LEGACY (<5 GiB), MID (5–10 GiB), LATEST (≥10 GiB). Roles are `LIVE_VOICE`, `VOICE`, and `LLM`. `DownloadingModelSetupService` downloads the models through `ModelDownloader` / `PlatformModelHttpClient`.
- **UI**: `App.kt` holds a `HorizontalPager` controlled by `AppShellStateHolder`. Pages are `BorrowCaptureScreen` (state in `CaptureUiStateHolder`) and `SavedMemoriesScreen` (state in `SavedMemoriesStateHolder`).
- **Platform split**: `commonMain` has the logic, UI, and interfaces. `androidMain` and `iosMain` have the recorders, players, permissions, device RAM, and HTTP clients.
- **Tests**: unit tests in `commonTest`. Instrumented tests in `androidInstrumentedTest`, including `AppServicesTest` and `KotbaseMemoryServiceTest`.
- **CI**: `.github/workflows/ci.yml` runs `./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug` on PRs to `main`.
- **Gotcha**: `versionName` comes from `app.version` in `gradle.properties`. `versionCode` is hard-coded in `composeApp/build.gradle.kts` and must be bumped separately (see Pull requests above).
