# Gap analysis

Snapshot: BorrowAI v1.9.7 (build 63). Evidence is from the code, `site/content/*`, `features/*`, and `CHANGELOG.md`. Scope is Android only: iOS-only items were removed from the pick list (G1, G2, G3, G10), and mixed items were narrowed to Android. Nothing was run on a device.

**Status at 1.17.0:** G4 is merged (#75, #83, and 1.17.0), with all of its phases done. G6 search, count, and category filters are merged (#76, #79), with keyboard and back behavior still open. G11 pause, seek, and skip are merged (#77), speed is in playback phase 2, and stored durations and recording pause remain. G5 has a merged design (#74) and no implementation. The speaker recognition UI is merged (1.13.0), but speaker analysis does not run until a speech model is pinned. The [pick list](pick-list.html) has the same items as checkboxes.
Size is a rough single-developer estimate: S = under a week, M = 1 to 3 weeks, L = more than 3 weeks.

## 1. What exists today

- **Android:** recording in a foreground service, live transcript (Whisper tiny, English), batch transcript (Whisper base or large-v3 by RAM tier), one-paragraph summary and AI categories (Gemma 4 E2B/E4B or Qwen3 0.6B through LiteRT-LM, optional GPU), saved list, memory detail with "Play recording", background activity pill, and a Settings screen.
- **iOS:** recording, playback, saved list, microphone permission. Nothing else is wired (`site/content/platforms.md`).
- **Data:** Couchbase Lite, local only. Soft delete and purge exist in the service layer. A speaker data layer exists. Its read-only UI is built on Android, but no speech model is pinned, so no analysis runs.
- **Settings:** two switches (`AppSettingsService.kt:7-12`, `SettingsScreen.kt`).

## 2. Missing features

| ID | Gap | Evidence | Why it matters | Size |
|----|-----|----------|----------------|------|
| G4 | **No way to delete a memory.** Soft delete and purge exist, but no UI calls `MemoryService.delete`. | `MemoryService.kt:41`; `KotbaseMemoryService.kt:158`; no UI caller | Users cannot remove a recording or transcript, which matters for privacy. | S |
| G5 | **No edit.** Title, summary, transcript, and categories cannot be changed in the UI. The service supports it through `update`, `updateCategories`, and `withEditedSummary`. | `MemoryService.kt`; `Memory.kt` (`withEditedSummary`, `withUserCategories`); no UI caller | Wrong AI output is permanent. | M |
| G6 | **No search, no filter.** Nothing searches titles, summaries, or transcripts. Categories cannot be used to filter. | No match for "search" in `commonMain` or the list state holder | Recall is the core job of a memory app. | S-M |
| G7 | **Titles are never generated.** `title` is saved as `""`, and the summary prompt asks for no title. The list renders an empty title line. | `RecordingMediaSessionService.kt:320`; `RecordingReconciler.kt:84`; `SavedMemoriesScreen.kt:324`, `:416` | Every card looks unfinished. | S |
| G8 | **No export or share.** No share sheet, file export, or transcript or audio export. | No match for `ACTION_SEND`, `UIActivityViewController`, or share in `composeApp` or `iosApp` | Users cannot get their data out, which fails the "your data is yours" promise. | M |
| G9 | **No backup or restore by the app.** Only OS backups, and they include memories (see G15). | `backup_rules.xml`; `data_extraction_rules.xml` | No controlled way to move to a new phone. | M |
| G11 | **Thin playback and capture controls.** No seek bar, skip, speed, pause and resume, bookmarks, input level, or max length. Durations are not stored. | `AudioPlayer.kt` (`play`, `stop`, interruption only); `AudioRecorder.kt` (`start`, `stop`); `Memory.kt` has no duration | Basic expectations of a recorder. | M |
| G12 | **English only.** Transcription is forced to English (`TRANSCRIPTION_LANGUAGE = "en"`) even though the models are multilingual. The UI has only `values/`, with no other locales. | `WhisperModelConfigFactory.kt:6`, used by `AndroidLiveTranscriber.kt:332` and `AndroidBatchTranscriber.kt:202`; `composeResources/values/` only | Non-English speech is transcribed badly or wrongly. A language picker is cheap. | S (setting) / M (UI strings) |
| G13 | **Settings is thin.** No theme, language, model management (see sizes, delete, re-download), storage use, LLM choice, data controls, privacy page, or about screen. | `SettingsScreen.kt`; `AppSettings` | Users cannot manage what the app stores or downloads. | M |
| G14 | **Light theme only.** One `lightColorScheme`, no dark mode. | `App.kt:34`, `App.kt:82` | Common expectation; cheap to add. | S |
| G15 | **Privacy claim vs OS backups.** The docs say audio and transcripts "do not leave the phone". Android auto-backup and device transfer include the database and recordings (only `models/` is excluded). | `index.md:3`; `AndroidManifest.xml:11-12`; `data_extraction_rules.xml:4-9`; `backup_rules.xml:4` | Either the claim is wrong or backup is an unexplained leak. Decide, document, and offer an opt-out or encryption. | S-M |
| G16 | **Recording notification may be hidden on Android 13+.** `POST_NOTIFICATIONS` is not requested, and targetSdk is 36. | `AndroidManifest.xml:3-7`; `build.gradle.kts:107`; `MicrophoneForegroundService.kt:38` | The "recording now" signal may not appear, which is a privacy and trust issue. | S |
| G17 | **No licenses or attribution screen.** The app ships Gemma, Whisper, sherpa-onnx, and LiteRT-LM. Check each license's notice obligations (Gemma has its own terms). | No match for licen, attribution, or open source | Compliance risk. | S |
| G18 | **No onboarding.** First launch starts multi-GB downloads with no explanation of the privacy model, storage needed, or the Wi-Fi choice. | No match for onboard or welcome | First-run drop-off and surprise. | S-M |
| G19 | **No manual retry.** Failed transcription, summary, or categorization retries only automatically (limits: transcription 5, summary 10, categories 3). | `Memory.kt` limits; no retry in UI | Users cannot recover a bad memory themselves. | S |
| G20 | **No import or share-in.** Audio from other apps (for example WhatsApp voice notes) cannot be brought in. | No document picker or `ACTION_SEND` intent filter | A large, easy intake source is closed. | M |
| G21 | **Transcripts have no timestamps or speakers.** `transcription` is one string. Speaker recognition has a read-only UI on Android, but no speech model is pinned, so no speakers are found yet. | `Memory.kt` (`transcription: String`); `SPEAKER_RECOGNITION.md:3` ("In progress") | Blocks tap-to-seek, chapters, and "who said what". | M-L |
| G22 | **One summary format.** The prompt asks for one short summary. No meeting notes, action items, or journal templates. | `SummaryPrompt.kt` (no title or format instructions); changelog 1.3.0 | Less useful for the most common note types. | S-M |
| G23 | **Categories are AI-only.** They cannot be renamed, created, or pinned by the user. At most three per memory. | Changelog 1.5.0 and 1.6.0; `MemoryService.createCategory` has only the categorizer as caller | Users cannot shape their own structure. | M (with G5) |
| G24 | **No widgets or app shortcuts.** No home-screen widget, Quick Settings tile, or app shortcut for one-tap capture. | Grep for widget, Glance, shortcut, and QuickSettings: no matches | One-tap capture is the main reason people use this kind of app. | M |
| G25 | **No app lock.** No biometric or passcode lock for a database of private audio. | Grep for biometric, FaceID, and LocalAuthentication: no matches | Expected for sensitive audio. | S-M |

## 3. Peer features we lack

Source: web research as of Nov 2026 (matrix in appendix A). "Unknown" in the matrix means unconfirmed, not absent.

Covered by gaps above, so not repeated: search (G6), export (G8), speed and pause (G11), quick capture and widgets (G24), multi-language (G12), user-managed folders (G23), AI summary (we have it on Android).

| ID | Feature | Peers that have it | Us | Size |
|----|---------|--------------------|----|------|
| P1 | Cross-device sync | Apple Voice Memos and Just Press Record (iCloud), Google Recorder (opt-in), Otter, Plaud, Bee, Voicenotes, Day One, Easy Voice Recorder (paid) | None | L |
| P2 | Trim and skip silence | Apple Voice Memos, Just Press Record, Easy Voice Recorder (skip silence; trim paid), Otter (paid) | None | M |
| P3 | In-recording bookmarks | Plaud, Bee | None | S-M (needs timestamps, G21) |
| P4 | Wear OS app (Apple Watch out of scope) | Apple Voice Memos, Google Recorder, Just Press Record, Voicenotes, Day One, Easy Voice Recorder | None | L |
| P5 | Integrations and API (Notion, Slack, Zapier, CRM) | Otter, Plaud, Bee, Voicenotes, Day One | None | M per integration; L for an API |
| P6 | AI chat over recordings | Otter, Plaud, Bee, Voicenotes, Day One | None (offline version: U1) | M-L |
| P7 | Action items and reminders | Otter, Plaud, Bee, Voicenotes | None (offline version: U4) | M |
| P8 | Speaker labels | Google Recorder, Otter | Read-only UI on Android, no analysis yet (G21) | M-L |

## 3a. Peer picture in short

- Most-held features across peers: search, cloud sync, export, AI chat, integrations, speaker labels, folders, action items, playback extras, quick-capture surfaces.
- Every peer with AI chat in the research (Otter, Plaud, Bee, Voicenotes) requires an account and processes in the cloud. Day One's processing is unconfirmed.
- Only Google Recorder and Easy Voice Recorder are on-device in the matrix, and neither offers chat. Google Recorder does on-device summaries.
- Held by only one or two peers: Action Button capture (Just Press Record), sound-event search (Google Recorder), CRM push (Otter), ambient wearable (Bee), journaling with mood tracking (Rosebud).

## 4. Unique differentiators

| ID | Idea | Builds on | Why it is distinctive | Size |
|----|------|-----------|-----------------------|------|
| U1 | **Ask your memories, offline.** Q&A over transcripts and summaries with the on-device Gemma model. Start with keyword retrieval, then add embeddings. | LiteRT-LM runtime with conversations (changelog 1.6.0); Couchbase store | Every AI-chat peer in the research runs in the cloud and needs an account (Day One unconfirmed). This is a private, offline "chat with your notes". | M-L |
| U2 | **Verifiable privacy.** A network ledger that logs every outbound request and shows "0 bytes of memories uploaded". Add an airplane-mode self-test. | `ModelNetworkPolicy`; `ModelSetupLog`; `PlatformModelHttpClient` | Turns the privacy claim into proof users can see. | S-M |
| U3 | **Who said it, offline.** Speaker chips and samples, with voiceprints kept on the phone. | Speaker data layer (1.8.0); `SPEAKER_RECOGNITION.md` plan | A headline feature no cloud-first peer can match on privacy. | M-L (already planned) |
| U4 | **Commitment tracker.** Detect promises and to-dos in a memory, then offer a local reminder. | Existing LLM pipeline; needs notification permission (G16) | Turns notes into follow-ups without sending audio anywhere. | M |
| U5 | **Memory echoes.** Resurface related memories (same category, same person, one year ago) through local notifications. | `createdAt`; categories | "On this day" for voice notes, with no cloud profile. | S-M |
| U6 | **Encrypted vault.** App lock plus encryption at rest for the database and recordings. | No lock today (G25); Couchbase Lite (check edition for encryption) | Positions the app as the safe place for sensitive recordings. | M |
| U7 | **Lend a memory.** Send one memory (audio, transcript, summary) to another BorrowAI user, device to device, with end-to-end encryption and no server. | Export format (G8); peer transfer | Fits the "borrow" name and needs no cloud. | L |
| U8 | **Smart chapters.** Split long recordings into topics with jump links. | Needs timestamps (G21); LLM pipeline | Makes hour-long recordings usable. | M-L |
| U9 | **Bring your own model.** Let power users load a checksum-verified on-device model. | `ModelCatalog` pins and SHA-256 checks already exist | Transparent and customizable on-device AI; niche. | M |

## 5. Suggested sequencing

1. **Quick fixes (about a week):** G7 auto-titles, G14 dark theme, G16 notification permission, G17 licenses, G15 backup decision.
2. **Core usability (two to four weeks):** G6 keyboard and back polish, G5 edit and recategorize, G11 stored durations, G8 export, G12 language setting, G19 manual retry.
3. **Differentiators:** U2 privacy ledger (cheap, on-brand), then U1 offline Ask, U3 speaker UI, U4 commitments.

## 6. Decisions needed

- Should memories be included in Android Auto Backup? Include and document, or exclude and offer an encrypted export? (G15)
- Keep English only for now, or add a language setting first? (G12)
- Which differentiator to lead with: U1 offline Ask, or U3 offline speakers?

## Appendix A. Competitor matrix (web research, Nov 2026)

Abbreviations: AVM Apple Voice Memos, GRC Google Recorder, JPR Just Press Record, OTT Otter.ai, PLD Plaud, L/B Limitless (legacy) / Bee, VNT Voicenotes.com, DAY Day One, EVR Easy Voice Recorder. "Unknown" means not confirmed in official sources.

| Feature | AVM | GRC | JPR | OTT | PLD | L/B | VNT | DAY | EVR |
|---|---|---|---|---|---|---|---|---|---|
| On-device processing (core) | yes | yes | yes | no | no | no | no | unknown | yes |
| Account required | no | no | no | yes | yes | yes | yes | unknown | no |
| Live transcript while recording | unknown | yes | yes | yes | unknown | unknown | yes | no | unknown |
| Speaker labels | no | yes | no | yes | unknown | unknown | unknown | no | no |
| 10+ transcription languages | no | yes | yes | no | yes | unknown | yes | unknown | no |
| AI summary | no | yes | no | yes | yes | yes | yes | unknown | no |
| AI chat or Q&A over history | no | no | no | yes | yes | yes | yes | yes | no |
| Full-text search | yes | yes | yes | yes | yes | unknown | yes | yes | yes |
| Tags or folders | yes | no | no | yes | unknown | no | unknown | yes | paid |
| Action items or reminders | no | no | no | yes | yes | yes | yes | no | no |
| Cloud sync across devices | yes | yes | yes | yes | yes | yes | yes | yes | paid |
| Export (txt, pdf, srt) | no | yes | yes | yes | unknown | yes | unknown | yes | no |
| Third-party integrations | no | no | no | yes | yes | yes | yes | yes | no |
| Playback speed | yes | unknown | yes | paid | unknown | unknown | unknown | unknown | yes |
| Skip silence | yes | unknown | unknown | paid | unknown | unknown | unknown | unknown | yes |
| Trim or edit audio | yes | unknown | yes | paid | unknown | unknown | unknown | unknown | paid |
| In-recording bookmarks | no | no | no | unknown | yes | yes | unknown | no | no |
| Widget, lock screen, or quick capture | unknown | no | yes | yes | no | no | unknown | unknown | yes |
| Watch app | yes | yes | yes | unknown | no | no | yes | yes | yes |
| Background recording | unknown | yes | yes | unknown | yes | yes | unknown | unknown | yes |
| Pause and resume recording | unknown | unknown | yes | unknown | unknown | no | unknown | unknown | yes |
| Pricing | free | free | $6.99 one-time | free plus subscription | device plus subscription | free (sunset) / Bee subscription | free plus $9 to $24 per month | free plus $50 to $75 per year | free plus ads or in-app purchases |

Sources: apps.apple.com (Voice Memos, Just Press Record, Day One); play.google.com (Recorder, Easy Voice Recorder); support.google.com (Recorder); otter.ai and otter.ai/pricing; plaud.ai and plaud.ai/pages/trust; limitless.ai (acquisition notice) and bee.computer; voicenotes.com and voicenotes.com/pricing; dayoneapp.com; Rosebud App Store listing. Apple's Voice Memos support guide was rate-limited during research, so Voice Memos claims rely on the App Store listing. Plaud and Bee subscription prices were not on their public pages.
