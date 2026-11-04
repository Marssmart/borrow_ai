# Pick list

**Scope: Android only.** Removed as iOS-only: G1, G2, G3, G10. Removed as non-Android surfaces: P14, P16. Narrowed to Android: G15, G20, G24, P4, U7. Use the IDs to pick work, for example "G4, G7, U2". The evidence for each G and U item is in the [gap analysis](gap-analysis.html). Peer features come from the competitor research in the gap analysis (Appendix A), unless marked "market", which means general market knowledge that was not checked in the research.

**Status at 1.12.1:** G4 phase 1 is merged (#75), and its Undo UI and accessibility phases are open. G6 search, count, and category filters are merged (#76, #79). G11 pause, seek, and skip are merged (#77). G5 has a merged design (#74) and no implementation. Items are checked off when their work is merged.

Size is rough for one developer: S = under a week, M = 1 to 3 weeks, L = more than 3 weeks. Items marked "verify on device" are inferred from code paths.

**Recommended first picks:** G7, G14, G16, G17 (quick wins), then G4 (Undo UI), G11 (speed and durations), G5, U2, U1. Decide G15 (backup behaviour) early, because it affects the privacy claim.

## A. Missing core features (G)

- [ ] **G4** Delete memories. Partly done: phase 1 state and commit rules are merged (#75). Still open: the Undo UI and accessibility. · S
- [ ] **G5** Edit title, summary, transcript, and categories. The service exists, the UI does not. · M
- [ ] **G7** Automatic titles (blank today). · S
- [ ] **G8** Export and share: audio (m4a), transcript (txt, md, and srt once timestamps exist), summary. · M
- [ ] **G9** Backup and restore that the user controls. · M
- [ ] **G11** Seek bar, skip ±15 s, playback speed, pause and resume recording, and a duration on each memory. Partly done: pause, seek, and skip are merged (#77). Still open: speed, stored durations, and recording pause. · M
- [ ] **G12** Transcription language setting (forced to English today). · S
- [ ] **G13** Real Settings screen: model sizes and removal, storage used, LLM choice, data controls. · M
- [ ] **G14** Dark theme (light-only today). · S
- [ ] **G15** Decide and document Android backup behaviour. Auto Backup and device transfer include memories today, which conflicts with "never leaves the phone". · S–M
- [ ] **G16** Request notification permission on Android 13+ so the recording notification shows (verify on device). · S
- [ ] **G17** Licenses and attribution screen (Gemma, Whisper, sherpa-onnx, LiteRT-LM). · S
- [ ] **G18** Onboarding: privacy model, storage needed, Wi-Fi and download choices. · S–M
- [ ] **G19** Manual retry for failed transcription, summary, and categories. · S
- [ ] **G20** Import audio files and share-in from other apps (Android intents and document picker). · M
- [ ] **G21** Transcript timestamps and speaker labels. The speaker UI is planned but not built. · M–L
- [ ] **G22** Summary formats: meeting notes, to-dos, journal entry. · S–M
- [ ] **G23** User-managed categories: create, rename, pin, filter. · M
- [ ] **G24** Android home-screen widget, Quick Settings tile, and app shortcuts for one-tap capture. · M
- [ ] **G25** App lock with biometrics or passcode. · S–M
- [ ] **G26** Large-library performance. All memories sit in one in-memory list (`MemoryService.getState`). Test with 1,000+ memories and paginate if needed. · S–M
- [ ] **G27** Recently deleted (30-day trash with restore), built on the existing soft delete. · S (after G4)
- [ ] **G28** Multi-select with bulk delete, export, and categorize. · M
- [ ] **G29** Sort options and date groups (Today, This week). The list is newest-first only. · S
- [ ] **G30** Recording quality and format settings. `AudioRecorder` takes only a file path. · S–M
- [ ] **G31** Storage management: size per memory, total use, auto-delete audio after N days while keeping the transcript. · M
- [ ] **G32** Battery-, thermal-, and charge-aware processing: run transcription and LLM work while charging or idle, and pause when the device overheats. · S–M
- [ ] **G33** Screen privacy: block screenshots and app-switcher previews on memory screens (no `FLAG_SECURE` today). · S
- [ ] **G34** Local diagnostics bundle for bug reports. Nothing leaves the phone until the user shares it. · S–M
- [ ] **G35** Accessibility pass beyond the capture button: list, detail, settings, large text, reading order. · S–M

## B. Peer features we lack (P)

- [ ] **P1** Cross-device sync. Peers: Apple Voice Memos and Just Press Record (iCloud), Google Recorder (opt-in), Otter, Plaud, Bee, Voicenotes, Day One. · L
- [ ] **P2** Trim and skip silence. Peers: Apple Voice Memos, Just Press Record, Easy Voice Recorder. · M
- [ ] **P3** Bookmarks while recording. Peers: Plaud, Bee. Needs G21 for seeking. · S–M
- [ ] **P4** Wear OS app for capture and playback. Apple Watch peers are out of scope. · L
- [ ] **P5** Integrations and API (Notion, Slack, Zapier, CRM). Peers: Otter, Plaud, Bee, Voicenotes, Day One. · M per integration
- [ ] **P6** AI chat over recordings, cloud version (offline version is U1). Peers: Otter, Plaud, Bee, Voicenotes, Day One. · M–L
- [ ] **P7** Action items and reminders, cloud version (offline version is U4). Peers: Otter, Plaud, Bee, Voicenotes. · M
- [ ] **P8** Speaker labels (same as G21). Peers: Google Recorder, Otter. · M–L
- [ ] **P9** Calendar integration: auto-name by meeting, attach memories to events. Market. · M
- [ ] **P10** Enhance recording with noise reduction. Market. · M
- [ ] **P11** Daily prompts and streaks for voice journaling. Peers: Day One, Rosebud. · S–M
- [ ] **P12** Mood and pattern insights, opt-in and on-device only (sensitive data). Peer: Rosebud. · M–L
- [ ] **P13** Draft documents or emails from past memories. Peer: Plaud. Needs U1. · L
- [ ] **P15** Team sharing and comments. Market. Conflicts with local-first, so decide first. · L

## C. Unique differentiators (U)

- [ ] **U1** Offline "Ask your memories" with the on-device Gemma model. No AI-chat peer is confirmed on-device. · M–L
- [ ] **U2** Verifiable privacy ledger: log every outbound request, show "0 bytes of memories uploaded", and offer an airplane-mode self-test. · S–M
- [ ] **U3** Offline speaker memory: who said what, with voiceprints kept on the phone. Already planned. · M–L
- [ ] **U4** Commitment tracker: detect promises and to-dos, then offer local reminders. · M
- [ ] **U5** Memory echoes: resurface related memories (same category, same person, a year ago) through local notifications. · S–M
- [ ] **U6** Encrypted vault: app lock plus encryption at rest. Check Couchbase Lite edition for encryption. · M
- [ ] **U7** Lend a memory: end-to-end encrypted device-to-device share (QR or Nearby Share). · L
- [ ] **U8** Smart chapters for long recordings, with jump links. · M–L
- [ ] **U9** Bring your own on-device model, checksum-verified. · M
- [ ] **U10** Meeting mode: calendar-aware naming, attendees from speaker chips, one action list per meeting. · M
- [ ] **U11** "Bookmark that" spoken during recording, detected in the live transcript. · M
- [ ] **U12** Voice-first accessibility mode: eyes-free capture and spoken status. · M
- [ ] **U13** Memory graph: people, places, and topics linked across memories, all on-device. · L
- [ ] **U14** Local-first multi-device sync with end-to-end encryption, as the privacy-preserving alternative to P1. · L

## D. Done (merged)

- [x] **G6** Search titles, summaries, and transcripts, plus a category filter. Merged: search and result count (#76), and category chips with clear filters (#79). Keyboard and back behavior remain (phase 3 of the design). · S–M

## In flight (not gaps)

- Speaker recognition: data layer shipped in 1.8.0. The UI is in progress, and the speech model is still planned (`features/speaker-recognition/SPEAKER_RECOGNITION.md`).
- GPU acceleration for the Android LLM: shipped in 1.9.7 (`features/gpu-support/GPU_SUPPORT.md`).
- Background activity indicator: shipped in 1.9.1.
