# Edit memories

Status: Planning (draft v0.1)
Visual guide: [visual-guide.html](./visual-guide.html)
Owner: BorrowAI native team

---

## 1. Summary

The service can change a memory, but no screen does. `MemoryService.update(id, draft)` (`MemoryService.kt`, line 32) and `updateCategories(id, categories)` (line 38) have no UI callers. A memory's title, summary, transcript, and categories are all fixed after they are saved.

This design adds an editor for those four fields, reached from the memory detail view. It also adds:

1. A service method that edits the fields in one write, and cannot clear the recording link by accident.
2. Two markers on the memory, so that an AI job never silently overwrites text the user wrote.
3. Explicit actions to ask for a new summary or new categories.
4. Cancel with a discard prompt, and a way back to the original transcript (phase 5).

The UI is Android only and uses Material 3. Shared rules and service changes live in `commonMain`.

---

## 2. Goals and non-goals

### Goals

- Edit the title, summary, transcript, and categories of a saved memory.
- Validate every field before saving, with clear messages.
- Make sure an AI job cannot replace a user's text, including a job that was already running when the user saved.
- Keep the recording link safe. Editing never clears the audio path.
- Make changes reversible: Cancel discards unsaved work, and the original transcript can be restored after a save.

### Non-goals (v1)

- Editing the audio. Trimming and re-recording are not planned.
- Edit history or versions beyond the original transcript.
- Speaker-attributed transcripts (see [Speaker recognition](../speaker-recognition/speaker-recognition.html)).
- Bulk edits across memories.
- Automatic regeneration after a transcript edit. The user asks for it.
- iOS UI and behaviour. Not designed here.

---

## 3. Terms

| Term | Meaning |
|---|---|
| Editor | The full-screen Android page that changes a memory's fields. |
| User-edited summary | A summary the user saved. Marked by `summaryEditedByUser`. |
| User-edited categories | A category list the user saved. Marked by `categoriesEditedByUser`. |
| Marker | A boolean on the memory that tells the AI pipeline to leave a field alone. |
| Regenerate | Clearing a field's marker and setting its status to pending, so the job writes a new value. |
| Original transcript | The text the speech model produced, kept once the user first edits the transcript. |

---

## 4. User stories and acceptance criteria

### US-1: Open the editor

As a user, I want to correct a memory from the screen where I read it.

- AC-1.1: The memory detail header has an **Edit** button (48 dp, labelled "Edit memory").
- AC-1.2: The editor opens full screen inside the Memories tab. The dock stays hidden, as it does in the detail view.
- AC-1.3: The editor shows the current values of all four fields.

### US-2: Edit the title

- AC-2.1: The title is optional and at most 80 characters (a new limit). A counter appears at 60 characters.
- AC-2.2: A blank title shows "Untitled memory" in the list and in the detail view (a new string). Titles are not generated today; `RecordingMediaSessionService.kt` (line 320) and `RecordingReconciler.kt` (line 84) create memories with `title = ""`.
- AC-2.3: Saving a changed title does not change any other field or status.

### US-3: Edit the summary

- AC-3.1: The summary is optional and at most 4,000 characters (a new limit).
- AC-3.2: Saving a non-blank summary stores it, sets `summaryEditedByUser`, and marks the summary as done. No AI job will overwrite it (US-6).
- AC-3.3: Saving a blank summary asks for a new one. The marker is cleared, the status becomes pending, and attempts reset. This is the existing rule in `withEditedSummary` (`Memory.kt`, line 117), made visible in the UI.
- AC-3.4: A helper line under the field says: "Clear the summary to have a new one written."

### US-4: Edit the transcript

- AC-4.1: The transcript is required once saved. A blank transcript is refused: "The transcript can't be empty. Delete the memory instead."
- AC-4.2: The transcript is at most 50,000 characters (a new limit).
- AC-4.3: The transcript is read-only while its status is pending or in progress, with the notice "Transcript is still being created." The service refuses such an edit too (§8).
- AC-4.4: A transcript that failed can be typed in. Saving sets its status to done and clears the last error (`withTranscription`, `Memory.kt`, line 98).
- AC-4.5: Saving a changed transcript does not regenerate the summary or the categories. A banner says: "The summary and categories were made from the earlier transcript." It offers **Write new summary** and **Suggest categories** (US-6). Both ask before replacing text the user wrote.
- AC-4.6: The first transcript edit keeps the original text (phase 5, Q-3). The section label changes from "Original transcription" (`SavedMemoriesScreen.kt`, line 454) to "Transcript", with an "Edited" tag.

### US-5: Edit categories

- AC-5.1: At most 3 categories per memory (`MAX_MEMORY_CATEGORIES`, `Memory.kt`, line 354).
- AC-5.2: The editor lists existing categories as selectable chips. A chip shows the selection state and reads as a checkbox to TalkBack.
- AC-5.3: A new category needs a name of 1 to 15 characters (`CATEGORY_NAME_MAX_LENGTH`, line 355) and a description of at most 100 characters (`CATEGORY_DESCRIPTION_MAX_LENGTH`, line 356). The UI refuses a name that already exists, ignoring case. The service does not check duplicates today (`createCategory`, `KotbaseMemoryService.kt`, line 187).
- AC-5.4: Saving a changed category list sets `categoriesEditedByUser`. The categorization job does not replace it.
- AC-5.5: Clearing all categories is allowed. The memory then shows "No categories". The job does not refill them. **Suggest categories** asks for a new list.

### US-6: AI never overwrites the user's text

- AC-6.1: While `summaryEditedByUser` is set, the summary job never writes a summary, and never changes the summary's status or attempt count.
- AC-6.2: While `categoriesEditedByUser` is set, the categorization job never writes categories and never changes their status.
- AC-6.3: A job that started before the user saved, and finishes after, is refused by the service. This is the race in §6.2.
- AC-6.4: Requesting a new summary or new categories asks first when the user's text would be replaced. Confirming clears the marker and sets the status to pending.

### US-7: Cancel, undo, and restore

- AC-7.1: **Cancel** with unsaved changes asks "Discard changes?" with **Keep editing** and **Discard**. Cancel with no changes closes at once.
- AC-7.2: System back does the same as **Cancel**.
- AC-7.3: After a save, **Restore original transcript** is available in the detail view if an original exists (phase 5). It asks first.

### US-8: Saving and failures

- AC-8.1: **Save** is disabled until a field has changed and every field is valid. While saving, the fields are read-only and the button shows progress.
- AC-8.2: On success the editor closes and the detail view shows the new values, from `getState()`.
- AC-8.3: On `MemoryError.Persistence` or `MemoryError.Malformed`, the editor stays open with the changes kept and the message "Couldn't save. Your changes are still here." and a **Retry** button.
- AC-8.4: On `MemoryError.NotFound`, the memory was deleted elsewhere. The editor closes and the snackbar says "This memory was deleted."
- AC-8.5: On `MemoryError.InvalidInput`, the editor stays open and shows the matching field message.

---

## 5. Flow

### 5.1 Saving

1. The user changes fields in the editor and taps **Save**.
2. The app validates the fields (§6.2). Invalid fields show a message and nothing is written.
3. `MemoryService.edit(id, edit)` runs once. It sets the markers for fields that changed, and it applies the transcript rules.
4. The detail view shows the stored values.

### 5.2 Requesting a new summary or categories

1. The user taps **Write new summary** or **Suggest categories**, from the banner or from an empty field.
2. If the user's text would be replaced, a dialog asks first.
3. `requestSummary(id)` or `requestCategorization(id)` clears the marker, sets the status to pending, and resets the attempt count.
4. The existing recovery job picks the memory up on its next run, as it does for any pending memory.

---

## 6. Data and service changes

### 6.1 Current behaviour that shapes this design

- `update(id, draft)` copies `draft.recordingPath` onto the memory (`KotbaseMemoryService.kt`, lines 141 to 152). A caller that omits the path clears the audio link. A new `edit` method avoids this by not taking a path.
- `update` does not touch the transcript status (`TranscriptionStatus`, `Memory.kt`), so a transcript edit on a pending memory could be overwritten later by the speech model, which claims pending memories (`claimNextPendingTranscription`).
- The summary job reads a snapshot (`findAll`, `MemorySummaryRecoveryJob.kt`, line 40). It calls the model, which can take up to 60 seconds (`DEFAULT_TIMEOUT_MILLIS`, line 122), and then calls `recordSummary` (line 84). `recordSummary` writes unconditionally (`KotbaseMemoryService.kt`, line 213).
- A failed attempt also changes the status without checking the text. `recordFinishedAttempt` (line 207) runs `afterFinishedAttempt` (`Memory.kt`, line 136), which sets the summary's status to pending (or to empty or given up, depending on the attempt count). A pending summary is then regenerated and overwritten. The same happens with categories: `recordCategorizationAttempt` (`KotbaseMemoryService.kt`, line 224) runs `afterFinishedCategorizationAttempt` (`Memory.kt`, line 229).

### 6.2 Validation

| Field | Rule | Status |
|---|---|---|
| Title | 0 to 80 characters | New limit |
| Summary | 0 to 4,000 characters. Blank means "write a new one". | New limit |
| Transcript | 1 to 50,000 characters, not blank, only when status is done or failed | New limit and rule |
| Categories | At most 3 distinct ids (`categoryIdsViolation`, `Memory.kt`). New names 1 to 15 characters. Descriptions up to 100. | Existing limits, plus a UI duplicate check |

The rules live in a new pure file, `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/domain/MemoryEdit.kt` (new). It holds a `MemoryEdit` value and a `memoryEditViolation(edit, transcriptionStatus)` function, which returns the first failing rule, or null. The transcript rules depend on the status, as §6.5 explains.

### 6.3 Service changes

| File | Change |
|---|---|
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/domain/Memory.kt` | Add `summaryEditedByUser: Boolean = false` and `categoriesEditedByUser: Boolean = false` to `Memory` (line 34). Both are additive, so an old document reads as `false`. Exclude marked memories from `isSummaryRetryable` (line 75) and `isCategorizationRetryable` (line 65). Set the summary marker in the edit path, next to `withEditedSummary` (line 117). Set the category marker in `withUserCategories` (line 214). Phase 5 adds `originalTranscription: String? = null`. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/domain/MemoryEdit.kt` (new) | `MemoryEdit` value and `memoryEditViolation`. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/MemoryService.kt` | Add `edit(id, edit)`, `requestSummary(id)`, and `requestCategorization(id)`. Remove `update` (line 32) once its callers and test double are moved. Phase 5 adds `restoreOriginalTranscription(id)`. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/kotbase/KotbaseMemoryService.kt` | Implement the new methods in one write. Store the two markers as document fields (the same pattern as `COLUMN_DELETED`). Guard the summary writes: `recordSummary` (line 213), `recordFinishedAttempt` (line 207), `recordEmpty` (line 210), `giveUpSummary` (line 216). Guard the category writes: `recordCategorization` (line 219), `recordCategorizationAttempt` (line 224), `giveUpCategorization` (line 227). A guarded write returns `Right(Unit)` and changes nothing. `edit` refuses a transcript change while the status is pending or in progress (`MemoryError.InvalidInput`). |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/summary/MemorySummaryRecoveryJob.kt` | No change in the job. The guard is in the service. The stored count can be one too high in the rare race, because a guarded write returns `Right`. The count is informational. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/category/MemoryCategorizationRecoveryJob.kt` | Same as the summary job. |

### 6.4 Why `Right` for a guarded write?

Returning an error would make the job log a failure and count an attempt. Returning `Right` tells the job it is finished, and it stops. The memory is never changed, so the user's text is safe either way. A new `MemoryError` kind would be cleaner but touches every caller, so Q-4 leaves it open.

### 6.5 Implementation notes (P1)

- **Transcript rule by status.** The transcript rules in §6.2 apply only when the transcript is done or failed (`TranscriptionStatus.isTranscriptEditable`). While it is pending or in progress, `memoryEditViolation` skips the transcript, and `edit` refuses any change to it. This is why the validation takes the status.
- **One write.** `edit` applies `Memory.withEdit`. A changed summary sets `summaryEditedByUser`. A blank one clears the marker and asks for a new summary instead (AC-3.3). A changed category list sets `categoriesEditedByUser`. A changed transcript keeps the summary and the categories (AC-4.5).
- **Guards.** Every AI summary write runs through `unlessSummaryEdited`, and every category write through `unlessCategoriesEdited`. A guarded write returns the memory unchanged. `FakeMemoryService` uses the same guards, so the race tests exercise them.
- **Stale results.** A recovery job passes the transcript it read (`basedOnTranscription`) to each result write. If the transcript changed since, the write is dropped as a guarded write: nothing is stored and no attempt is counted. This covers the case where a job is already running when the user edits the transcript.
- **Cleared categories stay cleared.** Clearing the list sets the marker, so the categorization job does not refill it (AC-5.5). The old rule, which made a cleared memory retryable, is replaced.
- **Tests.** `MemoryEditTest` covers the limits, the status rule, the markers, and the guards. The race tests are in `MemorySummaryRecoveryJobTest` and `MemoryCategorizationRecoveryJobTest`. The instrumented `KotbaseMemoryServiceTest` now calls `edit`. It was compiled, not run, because device runs are on hold.

---

## 7. UI (Android, Material 3)

### 7.1 Detail view

- An **Edit** button (`FilledTonalButton`, 48 dp minimum) sits in the header panel of `MemoryDetailScreen` (line 388), next to the **More actions** button from the delete design.
- The title shows "Untitled memory" when blank.
- The transcript section has the label "Transcript", plus the "Edited" tag when `originalTranscription` is set (phase 5).

### 7.2 Editor

- A full-screen page inside the Memories tab, shown in place of the detail view. The dock stays hidden. The pager stays locked (`App.kt`, line 128).
- Top app bar (Material 3 `TopAppBar`): navigation icon **Cancel** (close icon, content description "Cancel editing"), title "Edit memory", action **Save** (text button, disabled until valid).
- Fields in order: **Title** (`OutlinedTextField`, single line, counter at 60 characters), **Summary** (multi-line, helper text from AC-3.4), **Transcript** (multi-line, scrolls inside the page), **Categories** (chip group).
- Categories: `FilterChip` per existing category, with the selected state. A **New category** chip opens an `AlertDialog` with a name field (15 characters) and a description field (100 characters). The count reads "2 of 3" under the group.
- Banner (phase 3): after a transcript change is saved, the detail view shows the banner from AC-4.5 with **Write new summary** and **Suggest categories**. Each opens a confirmation when it would replace user text.
- Transcript read-only notice (AC-4.3): a small card with the text "Transcript is still being created." The field is disabled and keeps its content.

### 7.3 Cancel and discard

- **Cancel** with changes: `AlertDialog`, title "Discard changes?", buttons **Keep editing** (default) and **Discard**.
- Discard closes the editor and drops the draft. Drafts are kept in memory only, so a process death loses them (Q-6).

### 7.4 Save states

| State | What the user sees |
|---|---|
| Editing | Fields active. **Save** enabled once something valid has changed. |
| Invalid | The field shows an error message. **Save** disabled. |
| Saving | Fields read-only. **Save** shows a progress indicator. |
| Saved | Editor closes. Detail view shows the new values. |
| Failed | Editor open, changes kept. Message "Couldn't save. Your changes are still here." and **Retry**. |
| Deleted elsewhere | Editor closes. Snackbar "This memory was deleted." |

### 7.5 Implementation notes (P2)

- **Scope.** This phase edits the title and summary only. `MemoryEdit` carries the current transcript and categories unchanged, so the service still checks them. Phases 3 and 4 add those fields.
- **Overlay, not a pager page.** The editor is drawn over the detail view inside `SavedMemoriesScreen`, so the pager, the dock, and the detail back handling stay as they were. Cancel and system back both use the same discard check.
- **Save path.** `SavedMemoriesStateHolder.saveMemoryEdit` runs `MemoryService.edit` through the pending-write gate (`launchWrite`), so closing the app waits for an in-flight save. The result returns on the holder's scope.
- **Failed transcripts.** A title-only edit of a memory whose transcript is failed and blank is refused by the service, with its message shown. Phase 3 lets the user type the transcript in, which fixes this case.
- **Untitled.** The detail title shows "Untitled memory" when the title is blank (AC-2.2). The list card is not changed in this phase.
- **Tests.** `MemoryEditTest` covers `currentEdit` and a title-only edit. `SavedMemoriesDeleteTest` covers the holder's NotFound and InvalidInput results. `MemoryEditServiceTest` covers a failed transcript typed in, which becomes done. The screen is not unit-tested, because the project has no Compose UI test dependency.
- **Concurrent changes.** Only fields the user typed in are sent. A summary typed in is refused, with a message, when the memory's summary changed since the editor opened, so a background summary is not overwritten.
- **Categories (phase 4).** A chip per category, with at most 3 chosen. A fourth choice shows a message. The count reads "n of 3". New category opens a dialog: the name is up to 15 characters and the description up to 100. A duplicate name is refused, ignoring case, by `categoryNameTaken`, because the service does not check duplicates. A new category is added to the offered names and selected. Category choices are kept in memory only (Q-6).
- **Leaving the editor.** While the editor is open the detail nav bar is hidden, so its Back and Memories buttons cannot skip the discard check. System back and Cancel use the discard check.
- **Deleted elsewhere.** A memory removed from the store while it is open closes the editor with "This memory was deleted." The save path's NotFound uses the same notice.

---

## 8. Accessibility

- Every field has a visible label. The helper and error texts are attached to the field, so TalkBack reads them with it.
- The character counter is part of the supporting text. It is not a live region, so it is not read on every key press.
- Errors use `isError` on the field and a plain message. The message is read when focus enters the field.
- A category chip is a toggle. It uses `Role.Checkbox` and the selected state, so TalkBack announces "checked" or "not checked". The "maximum 3 reached" message is announced when the user tries a fourth.
- IME actions: Title and Summary use **Next**. Transcript uses the default new-line action, and **Done** hides the keyboard.
- The discard dialog gives focus to **Keep editing** first.
- Every touch target is at least 48 dp.

---

## 9. Platform split

| Layer | Location | Contents |
|---|---|---|
| Domain and rules | `commonMain` | `Memory` markers, `MemoryEdit`, validation |
| Persistence | `commonMain` | `KotbaseMemoryService` edit and guard changes |
| Jobs | `commonMain` | No code change; the guards in the service do the work |
| UI | `commonMain`, Compose Material 3 | Detail button, editor, dialogs, banner |
| iOS | Not designed here | Shared changes compile, but iOS screens and behaviour are out of scope |

---

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| A running AI job overwrites a user's text | Lost edit | Service guards on every summary and category write (AC-6.3). Tested with a job that finishes after an edit. |
| A failed job demotes a user's summary to pending | Later overwrite | Guards also cover `recordFinishedAttempt`, `recordEmpty`, and the category attempt path. |
| Editing a memory clears its audio link | Playback stops working | `edit` takes no path (§6.3). Tested. |
| Transcript edited while the speech model is working | Text overwritten later | Edit refused while pending or in progress (AC-4.3). Tested. |
| Original transcript doubles storage | Larger database | One copy per edited memory, phase 5, decision Q-3. |
| User loses a long edit to a crash | Frustration | Drafts are in memory only (Q-6). Decision open. |

---

## 11. Open questions

| # | Question | Decision |
|---|---|---|
| Q-1 | Is 80 characters right for a title? | **Open.** Default 80. |
| Q-2 | Are 4,000 (summary) and 50,000 (transcript) right limits? | **Open.** Defaults above. Check the longest real transcript first. |
| Q-3 | Keep the original transcript (storage cost) so it can be restored? | **Open.** Default: yes, in phase 5. |
| Q-4 | Should a guarded write return a dedicated `MemoryError` instead of `Right`? | **Open.** Default: keep `Right` (§6.4). |
| Q-5 | Should clearing all categories be refused, or allowed with **Suggest categories**? | **Decided: allowed** (AC-5.5). |
| Q-6 | Should unsaved drafts survive process death? | **Open.** Default: no. |
| Q-7 | Should the service reject duplicate category names, or only the UI? | **Open.** Default: UI only, for now. |
| Q-8 | Should a new summary or category list replace user text without asking? | **Decided: no.** It asks first (AC-6.4). |
| Q-9 | Do the editor screens (the overlay over the detail view, the Cancel and discard prompt, the save, failure, and deleted states, and the focus on Keep editing) look and behave as §7 describes on a device, and does TalkBack read the fields and errors? | **Open.** The save rules are unit-tested. The screens are not. Not run on a device. |

---

## 12. Phased rollout

| Phase | Scope | Exit criteria |
|---|---|---|
| P1 Service and markers | `MemoryEdit`, markers, `edit`, `requestSummary`, `requestCategorization`, guards, retry exclusions, `update` removed | commonTest and instrumented tests green (§13). Guards hold in the race tests. |
| P2 Title and summary editor | Editor page, title and summary fields, save and failure states | US-1, US-2, US-3, US-8 met on Android. |
| P3 Transcript and banner | Transcript field and rules, read-only notice, banner with the two actions | US-4 met on Android. |
| P4 Categories | Chip group, new category dialog, duplicate check in the UI | US-5 and AC-6.2 met. |
| P5 Original transcript | `originalTranscription`, "Edited" tag, **Restore original transcript** | AC-4.6 and AC-7.3 met. Storage cost checked. |

---

## 13. Test plan

### Unit (commonTest)

- `MemoryEdit` validation: each limit at its boundary, a blank transcript refused, the category rules from `categoryIdsViolation`.
- Applying an edit sets `summaryEditedByUser` only when the summary text changed, and sets `categoriesEditedByUser` only when the list changed.
- A blank summary clears the marker, sets the status to pending, and resets attempts.
- A marked memory is excluded by `isSummaryRetryable` and `isCategorizationRetryable`.
- `MemorySummaryRecoveryJobTest` (`composeApp/src/commonTest/kotlin/org/deer/borrowai/data/service/summary/MemorySummaryRecoveryJobTest.kt`): the fake service changes the summary while the summarizer is running. The result must not overwrite it.
- `MemoryCategorizationRecoveryJobTest` (`composeApp/src/commonTest/kotlin/org/deer/borrowai/data/service/category/MemoryCategorizationRecoveryJobTest.kt`): the same race for categories, plus a failed attempt after a user edit.
- Transcript edit refused while pending or in progress, and accepted (and set to done) while failed.
- The existing `FakeMemoryService` (`composeApp/src/commonTest/kotlin/org/deer/borrowai/data/service/FakeMemoryService.kt`) implements `edit`, `requestSummary`, and `requestCategorization`.

### Instrumented (androidInstrumentedTest)

- `KotbaseMemoryServiceTest` (`composeApp/src/androidInstrumentedTest/kotlin/org/deer/borrowai/data/service/kotbase/KotbaseMemoryServiceTest.kt`): edit persists, the markers round-trip, a document without markers reads as `false`, a guarded write leaves the summary unchanged, and the recording path survives an edit.

### Manual (device)

- Edit each field, save, and check the detail view and the list.
- Blank a summary and wait for a new one.
- Change a transcript, read the banner, then request a new summary and check it asks first.
- Start an edit while a summary is being generated, save the edit, and confirm the summary is not replaced.
- Cancel with changes, then Discard. Confirm nothing was saved.
- Force a save failure (for example, a full disk) and confirm the editor keeps the text.
- Full TalkBack walk-through of the editor and the category chips.

---

## 14. Definition of done

- [ ] Markers, guards, and retry exclusions are in place and tested (§13).
- [ ] No edit can clear the recording path or overwrite a user-edited field.
- [ ] Limits and messages match §6.2 and the string resources.
- [ ] TalkBack walk-through passes on Android.
- [ ] `visual-guide.html` matches the built UI, or the differences are noted.
- [ ] Phase 5 storage cost is measured before it ships.
