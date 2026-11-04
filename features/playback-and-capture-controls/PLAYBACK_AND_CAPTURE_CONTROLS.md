# Playback and capture controls

Status: P1 built. P2 to P5 are planned (draft v0.2)
Visual guide: [visual-guide.html](./visual-guide.html)
Owner: BorrowAI native team

---

## 1. Summary

Playback and recording are minimal today.

- Playback has **play** and **stop** only. `AudioPlayer` exposes `play(path)` and `stop()` (`AudioPlayer.kt`, lines 9 and 12). The detail view shows no progress.
- Playback stops when another app takes audio focus (`AndroidAudioPlayer.kt`, lines 17 to 22). It is not paused, so the position is lost.
- Recording has **start** and **stop** only (`AudioRecorder.kt`, lines 12 and 15). There is no pause.
- Memories store no duration. `RecordingResult.elapsedMillis` is computed when a recording stops (`RecordingMediaSessionService.kt`, line 348). It is shown on the capture screen, then dropped. `MemoryDraft` has no duration field (`Memory.kt`, line 323).

This design adds:

1. A seek bar with elapsed and total time, skip back and forward 15 seconds, and play and pause, in the detail view.
2. Playback speed: 0.75×, 1×, 1.25×, 1.5×, and 2×.
3. Pause that keeps the position, and interruptions that pause instead of stopping.
4. Pause and resume during tap-to-record.
5. A stored duration for each memory, taken from the audio file, so pauses are not counted. Older memories show "Duration unknown" until they are played.

Background playback with notification and lock-screen controls is out of scope for v1. It needs its own design (§12, P5). The UI is Android only and uses Material 3.

---

## 2. Goals and non-goals

### Goals

- Show elapsed and total time for a memory, and let the user seek.
- Skip back and forward 15 seconds.
- Change playback speed without changing pitch.
- Pause and resume playback, keeping the position.
- Pause and resume a recording without losing audio or time.
- Pause, not stop, when another app or a call takes the audio.
- Store a duration for every memory saved after this release, and show an honest "Duration unknown" for older ones.

### Non-goals (v1)

- Background playback, notification, and lock-screen controls (P5).
- Pause during hold-to-record. A press-and-hold recording ends on release.
- Trimming, editing, or re-recording audio.
- Waveforms.
- Saving the speed between launches. It resets to 1× when the app starts (Q-4).
- Playback speed for live transcription. Not applicable.
- iOS UI and behaviour. The shared interfaces change so that iOS still compiles, but no iOS behaviour is designed here.

---

## 3. Terms

| Term | Meaning |
|---|---|
| Position | Where the player is in the file, in milliseconds. Read from the player. |
| Seek | Moving the position to a chosen time. |
| Skip | Moving the position by ±15 seconds, clamped to the start and the end. |
| Speed | The playback rate: 0.75×, 1×, 1.25×, 1.5×, or 2×. Pitch stays the same. |
| Pause | Stopping the output and keeping the position. The audio focus is released while paused. |
| Interruption | Another app or a call takes the audio focus while the memory plays. |
| Active elapsed | The recording time that was not paused. This equals the audio length in the file. |
| Duration | The length of the finished audio file, read from the file. |
| Duration unknown | A memory saved before this release that has not been played yet, so no duration is stored. |

---

## 4. User stories and acceptance criteria

### US-1: Seek and see the time

As a user, I want to see how far into a memory I am and move to a point in it.

- AC-1.1: The playback panel in the detail view shows the elapsed time on the left and the total on the right, as m:ss, or h:mm:ss for an hour or more.
- AC-1.2: A seek bar (Material 3 `Slider`) spans the panel. Dragging it moves the position. If playback was running, it continues from the new position.
- AC-1.3: The position updates at least four times a second while playing (every 250 ms).
- AC-1.4: Seeking while paused keeps the pause.
- AC-1.5: When playback reaches the end, it pauses at the end. The play button then plays again from the start.
- AC-1.6: The seek bar and total time show "Duration unknown" for a legacy memory until it has been played once (US-7). Play still works.

### US-2: Skip 15 seconds

- AC-2.1: **Back 15 seconds** and **Forward 15 seconds** buttons sit either side of play and pause.
- AC-2.2: A skip is clamped at 0 and at the total duration.
- AC-2.3: Skip works while playing and while paused.
- AC-2.4: The forward skip is disabled while the duration is unknown. The back skip is still available.

### US-3: Change the speed

- AC-3.1: A speed button shows the current speed, for example "1×". Pressing it opens a menu with 0.75×, 1×, 1.25×, 1.5×, and 2×. The current choice is marked as selected.
- AC-3.2: Changing the speed applies at once. It keeps the position and the playing or paused state.
- AC-3.3: The speed applies to every memory for the rest of the app session, and resets to 1× when the app starts.
- AC-3.4: Pitch stays at its natural value. The speed is set with `PlaybackParams` (pitch 1.0).

### US-4: Pause and resume playback

- AC-4.1: While a memory plays, its play button shows **Pause**. Pausing keeps the position.
- AC-4.2: **Play** resumes from the paused position.
- AC-4.3: Only one memory plays at a time. Starting another stops the first, as today.
- AC-4.4: The list card and the detail view show the same playing and paused state for a memory.
- AC-4.5: Pausing releases the audio focus, so other apps can play while the memory is paused. Resuming requests it again.

### US-5: Pause and resume a recording

- AC-5.1: While a tap-to-record recording runs, a **Pause** button appears under the microphone button. While paused, a **Resume** button replaces it.
- AC-5.2: Resume continues the same file. Pausing leaves no gap in the audio file.
- AC-5.3: The microphone button still stops and saves the recording, whether it is paused or running.
- AC-5.4: Hold-to-record shows no pause control.
- AC-5.5: The elapsed time counts only active recording time. It stops while paused.
- AC-5.6: Live transcription pauses with the recording. The live text stops changing while paused, and resumes with the recording.
- AC-5.7: The notification text changes to "Recording paused" while paused, and back to "Borrow AI is recording from the microphone" on resume.

### US-6: Interruptions pause, they do not stop

- AC-6.1: When another app takes the audio focus during playback, playback pauses at the current position. The state is "paused by another app", and the panel shows the notice "Paused because another app started audio."
- AC-6.2: When a transient loss ends (the focus is regained), playback resumes only if it was playing before the interruption. A permanent loss leaves it paused, and nothing resumes it by itself.
- AC-6.3: A headset unplugged during playback pauses it (`ACTION_AUDIO_BECOMING_NOISY`). This is in P2 by default (Q-6).
- AC-6.4: A call or another interruption during a recording must not lose the audio already captured. The recording saves what it has (Q-2). The user sees a message that says so.

### US-7: Duration

- AC-7.1: Every memory saved after this release stores its duration in milliseconds, read from the finished file.
- AC-7.2: A memory saved before this release shows "Duration unknown" and no estimated total. Its duration is stored the first time it is played.
- AC-7.3: The stored duration is the audio length. It excludes paused time, because the file does.
- AC-7.4: A duration is never overwritten once it is stored.

---

## 5. Flow

### 5.1 Playback

1. The user taps **Play**. The service asks the player to play the file, and receives the duration from the player.
2. If the memory has no stored duration, the duration is saved (AC-7.2).
3. The service polls the player's position every 250 ms and publishes it.
4. **Pause** stops the output, keeps the position, and releases the audio focus.
5. **Play** requests the audio focus and resumes from the position.
6. At the end of the file, playback pauses at the end.
7. A platform interruption pauses playback and sets the notice. Regaining focus resumes it only if it was playing.

### 5.2 Recording

1. Tap to start. The service starts the recorder and the live transcriber (as today).
2. Tap **Pause**. The recorder pauses (no audio is written), the live transcriber pauses, the elapsed time stops, and the notification changes.
3. Tap **Resume**. The recorder and the live transcriber resume, and the elapsed time continues.
4. Tap the microphone button. The recorder finishes the file, and the memory is saved with the active duration (as today, plus the duration).

---

## 6. UI (Android, Material 3)

### 6.1 Playback panel in the detail view

The current `AudioPlaybackButtons` (`SavedMemoriesScreen.kt`, line 564) becomes a `PlaybackPanel`. It has three rows:

1. A full-width `Slider` (`valueRange` 0 to the duration). It is disabled while the duration is unknown.
2. Time labels: elapsed on the left, total on the right (AC-1.1).
3. Controls: **Back 15 seconds**, a 56 dp filled **Play** or **Pause** button, **Forward 15 seconds**, and the speed button on the right.

A notice line appears under the controls when the platform paused playback (AC-6.1). It uses `bodySmall`.

Icons are drawn with `Canvas`, as the app already does for its navigation icons (`drawBackArrow` in `BottomNavBar.kt`, line 188). The app has no icon dependency. Adding Material Icons Extended is an option (Q-9), but it is not needed for this set.

### 6.2 List card

- `PlaybackButton` (`SavedMemoriesScreen.kt`, line 582) shows **Pause** while the memory plays and **Play** otherwise.
- A duration label, such as "1:05", sits next to the date when the duration is known (P3, Q-8).

### 6.3 Capture screen

- While recording in tap mode, a secondary **Pause** button (`OutlinedButton`, 48 dp) sits under the microphone button. The microphone is `MicrophoneButton` in `BorrowCaptureScreen.kt` (line 314).
- While paused, the microphone label shows "Paused" and its elapsed time is frozen. A **Resume** button (`FilledTonalButton`) replaces **Pause**.
- Hold-to-record shows no pause control (AC-5.4). The gesture code (`detectTapGestures` and `onLongPress`, lines 448 and 458) is unchanged.
- The state comes from `CaptureUiStateHolder` (`toggleRecording`, line 37, and `stopRecording`, line 69).

### 6.4 Speed menu

- A Material 3 `DropdownMenu` with five `DropdownMenuItem`s, each with a radio-style selected state.
- The button label is the current speed. The menu closes after a choice.

### 6.5 States

| State | What the user sees |
|---|---|
| Idle | Play button, slider at 0, times "0:00" and total (or "Duration unknown"). |
| Playing | Pause button, slider moves, elapsed time updates. |
| Paused by user | Play button, position kept. |
| Paused by another app | Play button, position kept, notice "Paused because another app started audio." |
| Ended | Paused at the end. Play button restarts from 0. |
| Duration unknown | Slider disabled, total "Duration unknown", forward skip disabled. |
| Recording | Pause button under the microphone, elapsed running. |
| Recording paused | "Paused" label, elapsed frozen, Resume button. |
| Recording interrupted | Message: "Recording was interrupted. The audio up to here was saved." (Q-2). |

---

## 7. Accessibility

- The slider has the content description "Playback position" and the state description "0:24 of 1:05". TalkBack's adjustable actions step it.
- The skip buttons are labelled "Back 15 seconds" and "Forward 15 seconds". Each is a button role.
- Play and Pause have explicit labels: "Play recording" or "Pause recording", not only an icon.
- The speed button is labelled "Playback speed, 1×". Its menu items are radio items, and TalkBack reads the selected one.
- After a skip, the new time is announced once, as a polite live region. The slider itself is not announced on every frame.
- A platform pause is announced once: "Playback paused. Another app started audio."
- A recording pause or resume is announced once: "Recording paused" or "Recording resumed". The microphone button's state description reads "Paused at 0:42" while paused.
- All touch targets are at least 48 dp, including the slider thumb.
- Scrubbing must not change the memory page. Test that a drag starting on the slider does not flip the pager, and that a swipe elsewhere on the page still does (the pager is `SavedMemoriesScreen.kt`, line 144).

---

## 8. Service and data changes

### 8.1 Why the position comes from the player

Playback elapsed time is calculated from a monotonic clock today (`startPlaybackTicker`, `RecordingMediaSessionService.kt`, line 198). That clock keeps running through a pause, a seek, and a speed change, so it drifts from the audio. The player's position is the only correct value, so the service reads it (`AudioPlayer.positionMillis()`).

The current playback tick is one second (`TICK_INTERVAL_MILLIS`, line 401). That is too coarse for a seek bar, so playback polls every 250 ms. The recording tick stays at one second.

### 8.2 Why the duration comes from the file

The recording timer uses the wall clock (`epochClock.nowEpochMillis()`, lines 313 and 380). It counts paused time, while the file does not, because `MediaRecorder.pause()` stops writing. So the stored duration must come from the file.

- New recordings: read `METADATA_KEY_DURATION` from the finished file with `MediaMetadataRetriever` (API 10) when the recording is saved.
- Legacy memories: the duration returned by `MediaPlayer` when the memory is first played (`AndroidAudioPlayer.kt`, line 35) is stored. No extra file read is needed.

### 8.3 Files that change

| File | Change |
|---|---|
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/recording/AudioPlayer.kt` | New members with defaults: `pause()`, `resume()`, `seekTo(positionMillis)`, `positionMillis()`, `setSpeed(speed)`, and `controlsAvailable` (default false). The interruption handler receives `PlaybackInterruption` (new enum: `TRANSIENT`, `PERMANENT`). The existing `play(path)` and `stop()` stay as they are. |
| `composeApp/src/androidMain/kotlin/org/deer/borrowai/data/service/recording/AndroidAudioPlayer.kt` | Implement the new members with `MediaPlayer.pause()`, `start()`, `seekTo(Int)`, `currentPosition`, and `setPlaybackParams(PlaybackParams().setSpeed(speed).setPitch(1f))`. Change the focus listener (lines 17 to 22): transient loss and duck pause with `TRANSIENT`, and permanent loss pauses with `PERMANENT`. Abandon focus on pause, and request it on resume (AC-4.5). |
| `composeApp/src/iosMain/kotlin/org/deer/borrowai/data/service/recording/IosAudioPlayer.kt` | Compiles with the new interface. Changes are limited to the handler type. No iOS behaviour is designed here. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/recording/AudioRecorder.kt` | New members with defaults: `pause()`, `resume()`, and `pauseAvailable` (default false). |
| `composeApp/src/androidMain/kotlin/org/deer/borrowai/data/service/recording/AndroidAudioRecorder.kt` | Implement `pause()` and `resume()` with `MediaRecorder` (API 24, equal to minSdk). Add an `OnErrorListener`. The current code sets none (verified: no `setOnErrorListener` in `androidMain`), so a recorder error is not seen until stop. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/recording/LiveTranscriber.kt` | New members with defaults: `pause()` and `resume()`. The interface today has `start` (line 14) and `stop` (line 17) only. |
| `composeApp/src/androidMain/kotlin/org/deer/borrowai/data/service/speech/AndroidLiveTranscriber.kt` | Implement pause and resume by stopping and restarting the feed to the engine. It has its own `AudioRecord` (line 314, started at line 204), so pausing the recorder alone would not pause it. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/MediaSessionService.kt` | `Playing` (line 52) gets `paused: Boolean = false` and `speed: Float = 1f`. `Recording` (line 44) gets `paused: Boolean = false`. The interface (line 82) gets `pausePlayback()`, `resumePlayback()`, `seekPlayback(positionMillis)`, `skipPlayback(deltaMillis)`, `setPlaybackSpeed(speed)`, `pauseRecording()`, `resumeRecording()`, and `recordingPauseAvailable`, all with defaults. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/recording/RecordingMediaSessionService.kt` | Playback: pause, resume, seek, skip, speed, and end-of-file pause. The interruption handler (line 74) pauses instead of calling `stopActivePlayback()` (line 216). The position ticker replaces the clock-based one (line 198). Recording: pause and resume. Active elapsed time is the monotonic time minus paused time (`monotonicMillisSinceStart`, line 407). `RecordingResult.elapsedMillis` (line 348) is the active elapsed time. The memory is saved with its duration (`MemoryDraft`, built around line 320). On a first play with no stored duration, `recordDuration` is called. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/domain/Memory.kt` | Add `durationMillis: Long? = null` to `Memory` (line 34) and to `MemoryDraft` (line 323). Additive, so older documents read as `null`. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/MemoryService.kt` | Add `recordDuration(id, durationMillis)`. It writes only when no duration is stored (AC-7.4). |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/kotbase/KotbaseMemoryService.kt` | Store `durationMillis` in a new column (`create`, line 79; `applyTo`, line 487; `toMemory`, line 509; `queryRows`, line 385). Implement `recordDuration` with `updateActive` (line 276), guarded by "only when null". |
| `composeApp/src/androidMain/kotlin/org/deer/borrowai/data/service/recording/MicrophoneForegroundService.kt` | Update the notification text while paused (the text is `CONTENT_TEXT`, line 63). The foreground type stays `microphone` (line 28). |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/SavedMemoriesStateHolder.kt` | `togglePlayback` (line 110) pauses and resumes instead of stopping. Add seek, skip, speed, and the platform-pause notice. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/SavedMemoriesScreen.kt` | `PlaybackPanel` replaces `AudioPlaybackButtons` (line 564) and `PlaybackButton` (line 582). The card shows Pause while playing. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/BorrowCaptureScreen.kt` | Pause and Resume buttons under `MicrophoneButton` (line 314), and the paused label and state description. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/CaptureUiStateHolder.kt` | Pause and resume actions, and the paused flag. The stop path (line 69) is unchanged. |
| `composeApp/src/commonMain/composeResources/values/memory_strings.xml` and `capture.xml` | New strings for the controls, notices, duration labels, and descriptions. |

No manifest change is needed for v1. The microphone permission and the microphone foreground service already exist (`AndroidManifest.xml`, lines 7 and 24 to 27). P5 adds the media playback service and permission (§12).

### 8.4 Pausing, audio focus, and the interruption rules

- Pausing by the user, an unplugged headset, a permanent loss, or the end of the file releases the audio focus, so other apps can play (AC-4.5). A transient loss keeps the request registered, so the return of the focus is reported (§8.7).
- Resuming requests the focus again. If the request fails, the memory stays paused and the notice says so.
- A transient loss (`AUDIOFOCUS_LOSS_TRANSIENT`, and `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`, which speech should not duck) pauses with `TRANSIENT`. The service resumes only when the focus returns, and only if playback was running (AC-6.2).
- A permanent loss (`AUDIOFOCUS_LOSS`) pauses with `PERMANENT`. Nothing resumes it.

### 8.5 Recording interruptions

- `MediaRecorder` errors reach the app through an `OnErrorListener`. The service records the error and keeps the file.
- The service tries to finalise the file. If the finalise call fails, the partial file is kept only if it has a readable duration (`MediaMetadataRetriever`). Otherwise it is discarded, as it is today (`finishRecording`, line 307).
- The result is one of these: a saved memory with the message "Recording was interrupted. The audio up to here was saved.", or the existing failure state. Q-2 decides which one applies on each device.

### 8.6 Phase 1 as built

These decisions refine §8.3 and §8.4. They were made when P1 was built.

- `AudioPlayer.positionMillis()` returns `Long?`. It returns null when the player cannot report its position. The service then estimates the position from the monotonic clock, as before. This keeps the iOS player's behaviour unchanged.
- `AudioPlayer.controlsAvailable` is true on Android only. `MediaSessionService` also gets `playbackControlsAvailable` (default false), so the UI knows whether to show the panel. Without controls, the play button stops playback, the detail view shows the old single button, and playback ends by going idle. That is the iOS behaviour today.
- `resumePlayback()` returns `Either<PlaybackError, Unit>`. When a resume fails (for example, another app holds the audio focus), the memory stays paused at the same position and the service returns the new `PlaybackError.ResumeFailed`. Until P2 adds the notice in §8.4, that error shows in the existing error dialog.
- The Android player treats the completion listener and the error listener of `MediaPlayer` as the end of the file. From then on `positionMillis()` returns the duration, and the audio focus is released. The service keeps the playback paused at its end and does not call `pause()` on the finished player. Resume seeks to 0 before it starts the player, so playing again starts from the beginning.
- A seek sets a pending target that reads return until the platform confirms the seek, so the position does not jump back to the old value.
- The seek bar sends the seek when the drag or accessibility step ends (`onValueChangeFinished`). Material 3 1.4.0 calls that callback after each accessibility step too. Material 3 sets its own state description (the raw numeric value), which overrides one set outside the slider. The time text therefore goes in the content description, for example "Playback position, 0:24 of 1:05".
- Seek and skip need an active playback. An idle memory has no position to move, so the seek bar and the skip buttons are disabled for it.
- P1 stores no duration. The duration exists only while the memory plays or is paused. Once playback stops, the total time shows "Duration unknown" again until P3 stores durations. AC-1.6 and AC-7.2 are P3 targets.
- The focus listener still stops playback until P2 (§8.4). Instrumented tests for `AndroidAudioPlayer` (§13) are not in P1, because they need a device and an audio fixture.

### 8.7 Phase 2 as built

These decisions refine §8.3 and §8.4. They were made when P2 was built.

- `AudioPlayer.setInterruptionHandler` takes a `PlaybackInterruption`. The enum has `TRANSIENT` and `PERMANENT` from §8.3, and two more values. `FOCUS_REGAINED` tells the service that a transient loss ended, so it can resume (AC-6.2). `OUTPUT_UNPLUGGED` is sent when the audio output becomes noisy, such as a headset unplug (AC-6.3). Lambdas that do not name the argument still compile. Function references do not, so they must take the new parameter.
- A transient loss keeps the audio focus request registered. Abandoning it would stop the platform from reporting that the focus came back. A user pause, a headset unplug, a permanent loss, and the end of the file all release the focus (AC-4.5).
- `AudioFocusHeldException`, a `PlaybackUnavailableException`, marks a play or resume that another app's focus refused. A refused resume leaves the memory paused, sets `Playing.pausedByOtherApp`, and returns `PlaybackError.AudioFocusHeld`. The notice shows instead of the error dialog (Q-14). A refused play request still shows the playback-unavailable dialog, as it did before P2. §8.4 does not cover that case.
- `Playing.pausedByOtherApp` is true after a `TRANSIENT` or `PERMANENT` loss, so the notice shows (AC-6.1). A headset unplug leaves it false, so no notice shows.
- The service remembers whether playback was running when a transient loss paused it. The focus coming back resumes playback only then. A user pause or resume clears that memory, and so does a permanent loss. A refused resume keeps it, so the focus coming back later still resumes playback that was running.
- The speed is session state in the service, exposed as `MediaSessionService.playbackSpeed` (a `StateFlow`), so the speed button shows it while nothing plays. `Playing.speed` mirrors it while a memory plays. `AudioPlayer.setSpeed` keeps the speed for later `play` calls on the same player. If a device rejects a speed, the player keeps the speed it had, and the button still shows the chosen speed. That is an open device check.
- The Android player sets the speed with `PlaybackParams` (pitch 1.0) before it starts a memory, so a memory does not begin at 1× for a moment. If setting the speed starts a paused player, the player pauses it again (Q-3). Neither behaviour has been checked on a device.
- The speed button sits on the panel row beside the transport controls. The transport takes the remaining width, so the two do not overlap in layout. How the row fits on narrow screens is an open device check.
- Speaker samples (speaker recognition spec, section 8.3) play through the same player. A sample has no pause, so an interruption stops it, and nothing resumes it. A sample plays at the session speed. The wake-up before its end is scaled by that speed, so a sample stops at its end at any speed.
- The instrumented tests for `AndroidAudioPlayer` in §13 are still not written. They need a device and an audio fixture.

### 8.8 Phase 3 as built

These decisions refine §8.2 and §11. They were made when P3 was built.

- `Memory.durationMillis` and `MemoryDraft.durationMillis` are nullable. A new recording stores its duration when it is saved. `AudioDurationReader` reads the finished `.m4a` with `MediaMetadataRetriever`. If the read fails, the memory is saved with no duration, and nothing is estimated.
- A memory with no stored duration gets one on its first play (Q-7). The player supplies the value once the file is prepared. The write is `MemoryService.recordDuration`, and it is write-once. A stored duration is never overwritten, so a later play does not change it.
- The detail panel uses the player's duration while the memory plays or is paused. It falls back to the stored duration when idle. The list card shows the stored duration beside the date when it is known (Q-8).
- Kotbase stores the value as `durationMillis`, a number column that is removed when the duration is null. Reads accept both missing and present values, so older rows load unchanged.
- Tests: `RecordingMediaSessionServiceTest` covers the saved duration, the first-play write, and no overwrite. `KotbaseMemoryServiceTest` covers the round trip, reopen, and write-once. The Kotbase test compiles here but has not run, because instrumented runs are on hold.
- The audio duration read on a real device is an open check. It has not been seen on a device, so the list label and the first-play write are unverified on hardware.

### 8.9 Phase 4 as built

These decisions refine §8.3 and §8.5. They were made when P4 was built.

- Pause is tap-to-record only (AC-5.4). `RecordingMediaSessionService.pauseRecording` refuses a hold-to-record capture, and the capture screen shows no pause control for it.
- The active elapsed time is the wall clock since the start, minus every paused stretch, including a pause still in progress (AC-5.5). `RecordingResult.elapsedMillis` uses the same rule, so stopping while paused does not count the pause.
- A refused recorder pause (`RecordingUnavailableException`) keeps the capture running and leaves the state as Recording. A refused resume keeps the capture paused, so the user can resume again or save.
- `AndroidAudioRecorder` pauses with `MediaRecorder.pause()` and resumes with `resume()`. Both exist from API 24, which is minSdk. The notification text changes through `MicrophoneForegroundService.setPaused` (AC-5.7).
- Deviation from §8.3 and Q-1: the live transcriber does not close its `AudioRecord` while paused. It keeps reading the microphone and stops feeding the recognizer, and it clears its sample window on each paused read, so the resumed text is not joined to the audio before the pause. The engine is kept. The microphone therefore stays open while paused, and the Android microphone indicator stays on. The owner should review this. The alternative, stopping and restarting the `AudioRecord`, was not chosen because its read behaviour on pause is not verified.
- Deviation from §6.3 and §7: the screen-reader announcements ("Recording paused", "Recording resumed") and the "Paused at 0:42" state description are not built. TalkBack behaviour is unverified, so it is listed as an open item in the MR.
- Recorder errors (AC-6.4, §8.5): `AudioRecorder.setErrorListener` receives the `OnErrorListener` callback, which sets `interrupted` on the capture and shows "Recording was interrupted. The audio up to here was saved." The save then keeps the partial file only when `AudioDurationReader` reads a duration from it. Otherwise the capture is discarded as before. Whether a phone call produces this error, and whether the file can be finalised afterwards, is Q-2 and is unverified.
- No instrumented test exists for the recorder pause. The `AndroidLiveTranscriber` test runs on the JVM (`androidUnitTest`) with fakes.

---

## 9. Platform split

| Layer | Location | Contents |
|---|---|---|
| Interfaces and state | `commonMain` | `AudioPlayer`, `AudioRecorder`, `LiveTranscriber`, `MediaSessionService`, `Memory`, `MemoryService` |
| Service rules | `commonMain` | `RecordingMediaSessionService`, interruption rules, duration rules |
| Persistence | `commonMain` | `KotbaseMemoryService` column and `recordDuration` |
| Playback | `androidMain` | `AndroidAudioPlayer`, audio focus, speed |
| Recording | `androidMain` | `AndroidAudioRecorder`, `AndroidLiveTranscriber`, `MicrophoneForegroundService` |
| UI | `commonMain`, Compose Material 3 | Panel, list card, capture controls, speed menu |
| iOS | Not designed here | Interfaces have defaults. The iOS classes change only to compile. |

API levels (checked against the Android SDK `api-versions.xml` for platform 36.1):

| API | Level | Available at minSdk 24? |
|---|---|---|
| `MediaRecorder.pause()` and `resume()` | 24 | Yes |
| `MediaPlayer.setPlaybackParams` and `PlaybackParams` | 23 | Yes |
| `MediaPlayer.seekTo(int)`, `getCurrentPosition()`, `getDuration()`, `pause()`, `start()` | 1 | Yes |
| `MediaPlayer.seekTo(long, int)` | 26 | Not used. The `Int` overload is enough. |
| `MediaMetadataRetriever` | 10 | Yes |

`minSdk` is 24 (`composeApp/build.gradle.kts`, line 106). `targetSdk` is 36 (line 107).

---

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Clock-based position drifts after a seek or speed change | Wrong elapsed time | The position comes from the player (§8.1). |
| Pause leaves live transcription running | Text does not match the file | The live transcriber pauses too (AC-5.6). Tested. |
| A recorder error during a call loses audio | Lost recording | `OnErrorListener`, and the partial-file rule (§8.5). Q-2 tested on device. |
| Setting the speed while paused starts playback on some devices | Unexpected sound | Re-pause at once if playback starts. Q-3 tested on device. |
| Paused memory keeps other apps silent | Other apps lose audio | Pausing releases the audio focus (AC-4.5). |
| Seek bar moves the pager | Page flips while scrubbing | Slider gesture test (§7). |
| Legacy durations are guessed | Misleading totals | Never estimated. "Duration unknown" until played (AC-7.2). |
| Playback stops in the background | Playback ends unexpectedly | Playback is not a foreground service today, so Android can stop it. v1 does not change this. P5 does. |

---

## 11. Open questions

| # | Question | Decision |
|---|---|---|
| Q-1 | Can `AndroidLiveTranscriber` pause without losing its engine state? | **Decided: keep the engine and stop feeding it while paused.** The microphone stays open for live text (§8.9). |
| Q-2 | Does a phone call stop a `MediaRecorder` recording, and can the file be finalised afterwards? | **Open.** Test on devices. Default: keep the partial file when it has a readable duration (§8.5). |
| Q-3 | Does `setPlaybackParams` start playback when the player is paused? | **Open.** The re-pause is built in P2 (§8.7). Test on devices. |
| Q-4 | Should the speed be saved between launches? | **Decided: no** for v1 (AC-3.3). |
| Q-5 | Should background playback and notification controls ship in v1? | **Decided: no.** P5, with its own design. |
| Q-6 | Should a headset unplugged pause playback in v1? | **Open.** Default yes, built in P2 with a receiver for `ACTION_AUDIO_BECOMING_NOISY` (§8.7). Test on devices. |
| Q-7 | Should legacy durations be filled in by a launch job, not on first play? | **Decided: first play only.** No extra file reads. |
| Q-8 | Should list cards show durations? | **Decided: yes, in P3.** The label shows the stored duration when it is known. |
| Q-9 | Should icons come from Material Icons Extended, or from `Canvas` drawings? | **Open.** Default: `Canvas`, as the app already does. |
| Q-10 | For P5, use Media3 or keep `MediaPlayer` with a `MediaSession`? | **Open.** Check the Media3 minSdk against 24 first. The name `MediaSessionService` is already used by the domain interface, so Media3 classes need aliases. |
| Q-11 | Does `MediaPlayer` play from 0 after a seek to 0 that follows its completion? | **Open.** Device check for P1. The service seeks to 0 before it starts the player, so the default matches §5.1. |
| Q-12 | Does a drag that starts on the seek bar leave the memory page in place? | **Open.** Device check for P1 (§7, pager). |
| Q-13 | Do TalkBack and the seek bar's content description read as §7 describes? | **Open.** TalkBack check for P1 (§7). Material 3 overrides the state description (§8.6). |
| Q-14 | Does a resume that fails on the audio focus show the error dialog as the P1 build does? | **Open.** P2 shows the notice instead of the dialog (§8.7). Device check with another app holding the focus. |

---

## 12. Phased rollout

| Phase | Scope | Exit criteria |
|---|---|---|
| P1 Playback controls | Pause and resume, seek bar, time labels, skip ±15 seconds, position from the player, 250 ms ticks | US-1, US-2, US-4 met on Android. commonTest green (§13). |
| P2 Speed and interruptions | Speed menu, focus loss pauses, transient resume, notice, headset unplug (Q-6) | US-3 and AC-6.1, AC-6.2 met on a device with API 24 and on one with API 34 or later. |
| P3 Duration | `durationMillis` stored on save, stored on first play, "Duration unknown", list durations (Q-8) | US-7 met in JVM tests. Kotbase tests compile; the run is on hold. |
| P4 Recording pause and resume | Recorder pause and resume, active elapsed time, live transcriber pause, notification text, capture controls, recorder error listener | US-5 met in JVM tests. AC-6.4 and the call test (Q-2) are not verified on a device (§8.9). |
| P5 Background playback (separate design) | Media session, media playback foreground service, notification and lock-screen controls, notification permission on Android 13 and later | A separate design document is approved first. |

---

## 13. Test plan

### Unit (commonTest)

- `RecordingMediaSessionServiceTest` (`composeApp/src/commonTest/kotlin/org/deer/borrowai/data/service/recording/RecordingMediaSessionServiceTest.kt`, existing), with a fake player and a fake recorder:
  - Pause keeps the position. Resume continues from it.
  - Seek and skip are clamped at both ends.
  - Reaching the end pauses at the end.
  - A transient loss pauses and resumes only if playing. A permanent loss stays paused.
  - Speed is set and kept across pause and resume.
  - Recording pause excludes paused time from the elapsed time and from `RecordingResult.elapsedMillis`.
  - A save while paused stores the active duration.
  - `recordDuration` stores the first value only.
- `CaptureUiStateHolderTest` (`composeApp/src/commonTest/kotlin/org/deer/borrowai/CaptureUiStateHolderTest.kt`, existing): Pause and Resume are offered in tap mode only, and not in hold mode.
- Time formatting (new `m:ss` and `h:mm:ss` helper in commonMain): boundaries at 59 s, 1 h, and 0 ms.
- `FakeMemoryService` (`composeApp/src/commonTest/kotlin/org/deer/borrowai/data/service/FakeMemoryService.kt`, line 39): implements `recordDuration`.

### Instrumented (androidInstrumentedTest)

- `AndroidAudioRecorder`: record, pause for 2 seconds, resume, stop. The file's `METADATA_KEY_DURATION` matches the active time within 300 ms.
- `AndroidAudioPlayer`: seek and position, speed change while paused does not start playback (Q-3), and focus loss pauses (with a second focus holder).
- `KotbaseMemoryServiceTest` (`composeApp/src/androidInstrumentedTest/kotlin/org/deer/borrowai/data/service/kotbase/KotbaseMemoryServiceTest.kt`): `durationMillis` round-trips, and a document without it reads as `null`.

### Manual (device)

- API 24 device: pause and resume a memory, seek, skip, change speed, and check the position and the audio focus with another app.
- API 34 or later device: the same, plus a phone call during playback.
- Phone call during a recording (Q-2). Check the saved memory and its message.
- Unplug headphones during playback (Q-6).
- Record with two pauses. Check that the duration equals the active time, and that the live text stopped during each pause.
- TalkBack walk-through of the panel, the speed menu, and the capture controls.
- Scrub the slider on a memory page and confirm the page does not flip.

---

## 14. Definition of done

- [ ] Position, duration, and elapsed time follow §8.1 and §8.2.
- [ ] No pause or resume leaves a gap, a stale live transcript, or a held audio focus.
- [ ] Legacy memories never show an invented total.
- [ ] Q-2 and Q-3 are answered on real devices and recorded here.
- [ ] TalkBack walk-through passes on Android.
- [ ] `visual-guide.html` matches the built UI, or the differences are noted.
- [ ] P5 is not started until its own design is approved.
