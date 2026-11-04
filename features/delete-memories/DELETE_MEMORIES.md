# Delete memories

Status: Planning (draft v0.1)
Visual guide: [visual-guide.html](./visual-guide.html)
Owner: BorrowAI native team

---

## 1. Summary

A memory can be deleted in the data layer but not in the app. `MemoryService.delete(id)` is a soft delete. It sets `deleted = true`, hides the memory from `getState()`, and leaves the audio file and the record on disk. No screen calls it today. `DeletedMemoryPurger` removes the leftovers later.

This design adds:

1. A delete action in the memory detail view, plus a TalkBack action on each list card.
2. An Undo snackbar that lasts 10 seconds, instead of a confirmation dialog.
3. An empty state for a library with no memories.
4. Error handling and TalkBack behaviour for a destructive action.

The key decision: the memory is hidden at once, but the soft delete is written only when the Undo window ends. The purger never sees the memory during the window, so Undo needs no new service method and no schema change.

The UI is Android only and uses Material 3. Shared state rules live in `commonMain`.

---

## 2. Goals and non-goals

### Goals

- Delete a memory from its detail view in two taps, with no confirmation dialog.
- Undo a delete for 10 seconds. Undo restores the memory exactly as it was.
- Never remove audio or text while Undo is still possible.
- Show a useful empty state when the last memory is deleted.
- Give TalkBack users a delete action that does not depend on gestures.

### Non-goals (v1)

- 30-day trash and restore after the window. Follow-up G27.
- Multi-select delete. Follow-up G28.
- Swipe-to-dismiss on list cards. It conflicts with the page swipe in `SavedMemoriesScreen.kt` (`userScrollEnabled`, line 144), and it still needs a non-gesture route.
- Deleting from the Record tab or from a speaker library screen.
- iOS UI and behaviour. Not designed here.

---

## 3. Terms

| Term | Meaning |
|---|---|
| Soft delete | Sets `deleted = true` on the memory document. The memory leaves `getState()` but stays in the database until it is purged. |
| Purge | Permanent removal of the audio file, the speaker rows, and the memory document. Done by `DeletedMemoryPurger`. |
| Undo window | The 10 seconds after a delete, while the snackbar offers Undo. |
| Pending delete | A memory hidden in the UI whose soft delete has not been written yet. |
| Commit | Calling `MemoryService.delete(id)`. Happens when the Undo window ends or when a commit trigger fires (AC-3.2, AC-3.3). |

---

## 4. User stories and acceptance criteria

### US-1: Delete a memory from its detail view

As a user, I want to delete a memory from the screen where I read it.

- AC-1.1: The detail header has a **More actions** button (48 dp, content description "More actions for this memory"). It opens a menu with **Delete memory**.
- AC-1.2: Choosing **Delete memory** returns to the list and removes the memory from the list at once.
- AC-1.3: No confirmation dialog appears. A snackbar says "Memory deleted" with an **Undo** action.
- AC-1.4: The Undo window lasts 10 seconds. The snackbar closes when the window ends.

### US-2: Undo a delete

- AC-2.1: Tapping **Undo** within the window shows the memory again in its previous position (newest first), with its title, summary, transcript, categories, and recording unchanged.
- AC-2.2: Undo writes nothing to the database.
- AC-2.3: Undo does not restart playback. Playback was stopped when the delete started (AC-4.1).

### US-3: The delete becomes final after the window

- AC-3.1: When the window ends, `MemoryService.delete(id)` is called once. The memory stays hidden.
- AC-3.2: Leaving the Memories tab, or sending the app to the background, commits a pending delete at once.
- AC-3.3: A second delete commits the first one at once. At most one memory is pending.

### US-4: Playing, recording, and transcribing

- AC-4.1: Deleting a playing memory stops playback before the memory leaves the screen.
- AC-4.2: Deleting while a different memory is being recorded is allowed. The purge waits until the recording ends (the `isRecording` gates in `DeletedMemoryPurger.kt`, lines 32 and 46).
- AC-4.3: A memory whose transcript is still being made can be deleted. The transcription claim skips deleted memories (`claimNextPendingTranscription` in `KotbaseMemoryService.kt`), and a transcript that finishes late is refused with `MemoryError.NotFound`, because `completeTranscription` reads only active memories.
- AC-4.4: A memory whose save has not finished is not in the list yet, so it cannot be deleted there.

### US-5: Empty library

- AC-5.1: When no active memories remain, the list shows an empty state. It has the heading "No saved memories yet", the text "Record a voice note and it will appear here.", and a **Go to Record** button.
- AC-5.2: An Undo snackbar can still appear above the empty state. Undo returns to the list.
- AC-5.3: The empty state is not shown while a recording is being saved, so a new memory does not flash it. This needs the save status exposed on `MediaSessionService` (§8).
- AC-5.4: The same empty state is shown on first launch with no memories.

### US-6: Errors

- AC-6.1: If the commit fails with `MemoryError.Persistence` or `MemoryError.Malformed`, the memory returns to the list and a snackbar says "Couldn't delete this memory. It is still saved." It has no action.
- AC-6.2: If the commit returns `MemoryError.NotFound`, the memory is already gone. No error is shown, and the delete counts as done.
- AC-6.3: The list never hides a memory whose commit has not succeeded.

### US-7: TalkBack and accessibility

- AC-7.1: Each list card exposes a custom accessibility action, "Delete memory". It starts the same flow.
- AC-7.2: After a delete, accessibility focus moves to the "Saved memories" heading, never to a removed node.
- AC-7.3: The snackbar text is announced once, and the **Undo** action can be reached by swipe.

---

## 5. Flow

1. The user chooses **More actions**, then **Delete memory**. The memory is hidden, the detail view closes, and the snackbar shows for 10 seconds.
2. **Undo** within the window: the memory is shown again. Nothing is written.
3. The window ends: `delete(id)` is written once. The memory stays hidden.
4. The next purge run removes its audio, speaker rows, and record (§8.1).

---

## 6. UI (Android, Material 3)

### 6.1 Entry points

| Entry point | Status | Reason |
|---|---|---|
| **More actions** menu in the detail header | Primary, in v1 | The detail view has no bottom action bar. In detail mode the dock is replaced by `MemoryDetailNavBar` (`App.kt`, line 165). |
| List card long-press | Secondary, in v1 | Opens the same flow with no sheet. Discoverability is low, so the TalkBack action (AC-7.1) is the accessible route. |
| TalkBack custom action on each card | Accessible route, in v1 | AC-7.1. |
| Per-card trash icon | Rejected | Adds noise to every card, and the list is already dense. |
| Swipe to dismiss | Rejected | Conflicts with the page swipe (`SavedMemoriesScreen.kt`, line 144), and a gesture alone is not accessible. |

### 6.2 Menu

- A Material 3 `DropdownMenu` anchored to the **More actions** button in `MemoryDetailScreen`.
- v1 has one item, **Delete memory**. It uses the error colour (`MaterialTheme.colorScheme.error`, `#C93458` in the `BorrowColors` scheme in `App.kt`) and always has its text label. The colour is about 5:1 against white.
- Editing (G5) is a separate button, not a menu item, so destructive and editing actions are not mixed.

### 6.3 Undo snackbar

- A Material 3 `Snackbar` with the action **Undo**, hosted in `SavedMemoriesScreen`. `App.kt` has no `SnackbarHost`. The snackbar must sit above `BottomNavBar`, so it uses the same bottom reserve as the pager (`App.kt`, line 126).
- Duration: the state holder dismisses the snackbar when its own 10-second timer ends. The timer decides the commit time, not a snackbar preset.
- Text: "Memory deleted". Action: "Undo".
- Only one snackbar at a time. A second delete commits the first (AC-3.3) and shows the new message.

### 6.4 Empty state

- Shown in the list page, below the heading, when no active memories exist and no save is pending (AC-5.3).
- Heading (`headlineSmall`, marked as a heading for TalkBack): "No saved memories yet". Body (`bodyMedium`): "Record a voice note and it will appear here." Button (`FilledTonalButton`): **Go to Record**, which calls `appStateHolder.navigateToPage(CAPTURE_PAGE)`.
- The empty state replaces the cards only. The "Saved memories" heading stays.

### 6.5 Error snackbar

- "Couldn't delete this memory. It is still saved." It has no action and can be dismissed.
- The memory reappears by itself, because it was never hidden in the database (AC-6.3).

### 6.6 Screen states

| State | What the user sees |
|---|---|
| Normal | List of cards. **More actions** in each detail view. |
| Pending delete | Memory gone from the list. Snackbar "Memory deleted" with **Undo**, for 10 seconds. |
| Committed | Snackbar gone. Memory stays hidden until it is purged. |
| Commit failed | Memory back in the list. Error snackbar, no undo. |
| Empty library | Empty state (AC-5.1). |

---

## 7. Accessibility

- Touch targets: **More actions**, menu items, and **Undo** are at least 48 dp.
- Labels: the **More actions** button has the content description "More actions for this memory". The menu item is the text "Delete memory". Neither depends on colour or icon shape.
- Focus after delete: accessibility focus moves to the "Saved memories" heading. The heading must be focusable (`Modifier.focusable()`) and request focus after the card is removed (AC-7.2).
- Announcements: the snackbar text is announced once. The app already uses `LiveRegionMode.Polite` for status text (`BorrowCaptureScreen.kt`, line 261). The error snackbar follows the same pattern.
- Timing: 10 seconds is the minimum. Check it with TalkBack on a device, and lengthen the window if users need more time (Q-1).
- Empty state: the heading is a real heading for TalkBack. The **Go to Record** button has a plain label.

---

## 8. Service and data changes

No schema change and no new storage. The commit timing lives in the UI state holder. The purger is unchanged.

| File | Change |
|---|---|
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/SavedMemoriesStateHolder.kt` | Add the pending-delete state (`PendingMemoryDelete`), plus request, undo, and commit functions. The list shows `visibleMemories`, which leaves out the pending memories. `memories` keeps them, so the detail view can slide back with its memory (§8.3). Call `mediaSessionService.stopPlayback()` when the playing target is the memory being deleted. Commit on tab leave, on background, and on a second delete. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/PendingMemoryDelete.kt` (new) | A pure class for the window and commit rules, so the rules can be unit tested without Compose. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/app/AppServices.kt` and `data/app/PendingWrites.kt` (new) | `launchWrite` starts the soft-delete writes. `close()` waits for them before the database closes, through `PendingWrites`. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/SavedMemoriesScreen.kt` | **More actions** button and `DropdownMenu` in `MemoryDetailScreen` (line 388). Long-press and the TalkBack custom action in `MemoryCard` (line 272). `SnackbarHost`. Empty state in `MemoryListScreen` (line 225). |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/App.kt` | Pass a "go to Record" callback into `SavedMemoriesScreen`. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/MediaSessionService.kt` | Expose whether a save is pending. `RecordingMediaSessionService` already has `isBusy` (line 368), but the interface does not. Needed for AC-5.3. |
| `composeApp/src/commonMain/composeResources/values/memory_strings.xml` | New strings for the menu item, snackbar, Undo, error, empty state, and content descriptions. |

Verified and unchanged:

| File | Why it stays as it is |
|---|---|
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/MemoryService.kt` | `delete` (line 41) and `purge` (line 44) already exist. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/kotbase/KotbaseMemoryService.kt` | `delete` (line 158) sets the flag, and `readActive` refuses deleted memories. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/recording/DeletedMemoryPurger.kt` | The purge order and gates are already right (§8.1). |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/recording/RecordingReconciler.kt` | `findAll` includes deleted memories, so their paths are in `pairedPaths` (line 57). Their audio is not recreated. |

### 8.1 What the purger removes later

`DeletedMemoryPurger` handles each deleted memory in this order (`DeletedMemoryPurger.kt`, lines 49 to 59):

1. Audio: the `recordingPath`, and `recordings/<id>.m4a`, unless an active memory still uses that path.
2. Speaker rows: `speakerService.deleteForMemory(id)`. If this fails, the record stays.
3. The record: `memoryService.purge(id)`.

A failed step keeps the record, so the next run retries. The purger is created in `AppServices.kt` (line 286). It runs at launch and then every 15 minutes (`DEFAULT_INTERVAL_MILLIS`, line 84). It waits while a recording is in progress. Its work shows as **Cleaning up** in the background activity pill.

### 8.2 Why not delete immediately and restore?

An immediate soft delete plus a `restore(id)` method would also work. But the purger would then need a grace period, so it knows when the memory was deleted. That needs a new field (`deletedAtEpochMillis`) and a schema change. The deferred commit gives the same result for the user without either change. Q-2 records this decision.

### 8.3 Implementation notes (P1)

- **Commit scope.** Soft-delete writes start through `AppServices.launchWrite`, not on the Memories screen's scope, so a write keeps running after that page leaves composition. `AppServices.close()` refuses new writes first, then waits for the ones already started (`PendingWrites`) before the database closes. A write launched after close began is refused. Its delete is not written, so the memory returns on the next launch, the same safe failure as an app kill.
- **Commit triggers.** The 10-second window timer runs on the screen scope. Leaving the Memories tab is seen when its page leaves composition (`DisposableEffect`), which happens after the swipe settles. Going to the background is `LifecycleEventEffect(ON_STOP)`. A second delete commits the first one before it opens its own window.
- **Hidden while writing.** A memory stays hidden until its write returns. A `Persistence` or `Malformed` result shows it again and counts one error. `NotFound` counts as done (AC-6.2).
- **Stale timers.** Each window has a request id. A timer left over from an earlier window does nothing.
- **Visible list.** `SavedMemoriesUiState.visibleMemories` leaves out the pending memories. The `memories` field keeps them, so the detail view keeps its memory while the pager slides back to the list. The first draft filtered `memories` itself, which would have blanked the detail view during that slide.
- **Background tracking.** The soft-delete write is short and started by the user, so it is not reported as background activity. The purge that follows is still reported as **Cleaning up**.
- **Playback.** Requesting a delete stops playback of that memory, and cancels a playback confirmation waiting for it.

### 8.4 Implementation notes (P2)

- **More actions.** The project has no icon library, so the button uses the glyph "⋮" in a 48 dp `IconButton`. Its content description is "More actions for this memory", and TalkBack reads that label, not the glyph.
- **Save gate.** `MediaSessionService.saving` is a cold `Flow` derived from the pending saves. It starts no coroutine, so the save state is always current when collected. The empty state needs no visible memory and no running save (AC-5.3). It also waits until the memory list and the save state have each arrived, so it does not flash on the first frame.
- **Snackbar precedence.** A new delete dismisses an error still on screen, so its Undo is never queued behind that error. An error that arrives during an Undo window waits until the window ends.
- **Focus after delete.** The list heading is focusable. Focus moves to it once the pager has settled on the list (AC-7.2). If the heading is not attached, focus stays where it is.
- **Detail slide.** Deleting from the detail view calls `requestDelete` and then `backToList`. The detail keeps its memory until the slide finishes.
- **Snackbar placement.** The Undo snackbar is a bottom-aligned child of a `Box` around the pager, with 8 dp of padding. `App.kt` already pads the pager for the dock, so the snackbar sits above the dock. Not seen on a device (Q-6).

---

## 9. Platform split

| Layer | Location | Contents |
|---|---|---|
| State and commit rules | `commonMain` | `PendingMemoryDelete` and the state holder changes |
| UI | `commonMain`, Compose Material 3 | Menu, snackbar, empty state, TalkBack action |
| Playback stop | `commonMain`, through `MediaSessionService` | The existing `stopPlayback()` |
| Purge | `commonMain` (`DeletedMemoryPurger`) | No change |
| iOS | Not designed here | The shared changes compile on iOS, but iOS screens and behaviour are out of scope |

---

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| The app is killed during the Undo window | The delete is lost, and the memory returns on the next launch | Safe failure: nothing is removed. Documented in this doc. |
| Several deletes in a row | Several commits to track | At most one pending delete (AC-3.3). |
| TalkBack users miss the window | Undo is not reached in time | 10 seconds minimum, and a device test (Q-1). |
| The commit runs for a memory that another path already removed | `NotFound` | Treated as done (AC-6.2). |
| Playback continues for a hidden memory | Confusion, and audio plays after the delete | Playback stops when the delete is requested (AC-4.1). |

---

## 11. Open questions

| # | Question | Decision |
|---|---|---|
| Q-1 | Is 10 seconds enough with TalkBack on? | **Open.** Test on a device, and lengthen the window if needed. |
| Q-2 | Should the design use an immediate soft delete, plus `restore(id)` and a purge grace period? | **Decided: no** for v1 (§8.2). Revisit when the 30-day trash (G27) is designed. |
| Q-3 | Should a long recording (for example, over 5 minutes) need a confirmation dialog? | **Open.** Default: no. |
| Q-4 | Keep the long-press shortcut as well as the TalkBack action? | **Open.** Default: keep both. |
| Q-5 | Should any memory require a confirmation dialog? | **Decided: no.** Undo is the safety net. |
| Q-6 | Do the Undo snackbar, the empty state, the error snackbar, and the focus move after a delete look and behave as §6 describes on a device? | **Open.** The state rules are unit-tested; the screens are not. Not run on a device or emulator. Check on a device before phase 3 starts. |

---

## 12. Phased rollout

| Phase | Scope | Exit criteria |
|---|---|---|
| P1 State and commit | `PendingMemoryDelete`, state holder changes, playback stop, commit on leave, background, and second delete | commonTest green (§13). No audio file is removed inside the window. |
| P2 UI | **More actions** menu, Undo snackbar, empty state, error snackbar, focus after delete | US-1, US-3, US-5, and US-6 met on Android. |
| P3 Accessibility | TalkBack custom action, long-press, announcements, focus | US-7 met with TalkBack on a device. |
| P4 Follow-ups (not scheduled) | 30-day trash (G27), multi-select (G28) | Separate design documents. |

---

## 13. Test plan

### Unit (commonTest)

- `PendingMemoryDelete`: undo inside the window writes nothing (the `FakeMemoryService` test double records no `delete` call).
- The window end commits once. A second delete commits the first at once.
- Leaving the tab or backgrounding commits the pending delete.
- A playing memory is stopped when its delete is requested.
- A `Persistence` commit failure makes the memory visible again and sets the error message.
- A `NotFound` commit result is treated as success.
- The empty state is derived only when no memories are active and no save is pending.

Source files: `composeApp/src/commonTest/kotlin/org/deer/borrowai/data/service/FakeMemoryService.kt` (test double), and `composeApp/src/commonTest/kotlin/org/deer/borrowai/data/service/recording/DeletedMemoryPurgerTest.kt` (existing purge tests, extended to cover the window).

### Instrumented (androidInstrumentedTest)

- `KotbaseMemoryServiceTest` (`composeApp/src/androidInstrumentedTest/kotlin/org/deer/borrowai/data/service/kotbase/KotbaseMemoryServiceTest.kt`): a deleted memory is absent from `getState()`, `purge` removes its document, and deleting it again returns `NotFound`.
- Compose UI tests for the menu, snackbar, and empty state need a UI test dependency. `composeApp/build.gradle.kts` has none today, so this is an open decision.

### Manual (device)

- Delete from the detail view, then tap **Undo** within 10 seconds. The memory returns in place.
- Wait out the window. Confirm the memory stays hidden. After the next launch, confirm its files under `recordings/` are gone.
- Force-quit inside the window. Confirm the memory returns and nothing was removed.
- Delete a playing memory. Playback stops.
- Delete while another memory is being recorded. The purge waits until the recording ends.
- Full TalkBack walk-through: menu, snackbar, Undo, focus after delete, and the empty state.

---

## 14. Definition of done

- [ ] The window, commit triggers, and failure handling match §4 and §8.
- [ ] No audio file is removed inside the Undo window (test and device).
- [ ] The TalkBack walk-through passes on Android.
- [ ] `visual-guide.html` matches the built UI, or the differences are noted.
- [ ] Follow-ups G27 and G28 are listed and not started.
