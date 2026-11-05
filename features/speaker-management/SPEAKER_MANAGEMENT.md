# Speaker management and assignment

Status: Implemented in the current worktree; not yet released. The approved speaker-name limit is 64 characters.

Scope: Android speaker-library management and assignment flows

Related design: [Speaker recognition](../speaker-recognition/speaker-recognition.html) and its [visual guide](../speaker-recognition/visual-guide.html)

---

## 1. Recommendation

Treat the speaker library as a private address book for voices, with memory occurrences as the links between an identity and the places it was heard. Keep management close to both contexts: provide a **Speakers** entry in the Saved Memories header and a **Manage speakers** entry in Settings; let an unknown or matched speaker card open the same assignment flow from a memory.

Use one library list, one speaker detail page, and one assignment sheet. The detail page provides the identity's recent appearances and an explicit review path for automatic matches. Creating, renaming, assigning, and correcting a match should update the memory list and detail from committed local state. Merge and delete are destructive identity operations: explain exactly what changes and require confirmation. Do not silently remove memories or original recordings.

This document records the approved interaction design and its implementation. The library, manual assignment, correction, and merge flows are implemented in the current worktree, but are not released until the change is reviewed and merged. The linked specification's §9.3 assign sheet and §9.5 library screen are implemented as described here.

## 2. Scope and principles

### Goals

- Give people a clear, reachable place to inspect and maintain named speakers.
- Assign an unknown occurrence to an existing identity or create-and-assign a new one.
- Make automatic matches inspectable and easy to correct without suggesting that recognition is certain.
- Preserve the recording, memory, occurrence order, sample time range, and other speaker links when changing one identity.
- Make deletion and merging understandable before the user commits.
- Keep speaker records, voice embeddings, and analysis on-device, under the explicit consent and withdrawal controls already designed for speaker recognition.

### Out of scope

- Speaker-attributed transcript text, scripted voice enrollment, cloud matching, or sharing speaker identities.
- iOS speaker management until speaker analysis and the relevant playback support exist on iOS.
- Bulk reassigning many occurrences or an automated "merge duplicates" action.
- Treating a voice match as proof of a real-world identity.

### Interaction principles

1. **Identity is user-owned.** Names are labels the user supplies; automatic matches are suggestions and can be wrong.
2. **Local-first and consent-gated.** Do not fetch, export, or transmit speaker data as part of these flows.
3. **Keep the source visible.** Every occurrence action begins from a memory and offers its sample when the original recording is available.
4. **Never change more than the user chose.** Assigning one occurrence does not rename, move, or reassign unrelated occurrences.
5. **No color-only meaning.** Name, status text, and accessible control labels accompany speaker colors.

## 3. What exists today

| Area | Confirmed behavior | Design implication |
|---|---|---|
| Local speaker data | `SpeakerService` reads, creates, renames, and deletes speakers; names are trimmed and unique ignoring case. Speaker data is stored separately from memories. | Reuse the service's name rules. Do not claim merge or occurrence-assignment operations already exist. |
| Speaker records | A speaker has a stored palette index, an embedding count capped at 10, and a last-used timestamp. The service lists speakers most recently used first. | Show a stable color, and use recent activity as the initial ordering. Counts and last-seen dates need occurrence data. |
| Detected occurrences | A memory occurrence has a 1-based ordinal, sample range, speech duration, a nullable speaker link, and `NONE`, `AUTO`, or `MANUAL` link type. Manual links survive analysis rebuilds. | Show whether a visible name came from automatic matching or a user assignment. |
| Automatic matching | The analyzer compares occurrence centroids to same-model library embeddings. The current proposed similarity threshold is 0.65. Rejected speaker IDs are excluded. | Use cautious, non-certainty language. Do not expose a confidence percentage; the UI has no calibrated user-facing score. |
| Detail samples | The memory detail can show occurrence cards and play a time range from the original recording. Missing audio removes playback but leaves speaker data visible. | Keep sample playback on the occurrence card; a library identity by itself is not a separate audio file. |
| Delete | Deleting a speaker removes its library-linked embeddings, changes its occurrences to unknown, and leaves memories untouched. Samples are time ranges in the original recording. | Explain that identity links and voiceprints are removed, but the memory and its audio remain. |
| Consent | Analysis is off until current consent is accepted and verified models are available. Turning analysis off preserves the library; withdrawal clears speaker data and turns analysis off. | Keep ordinary disable and consent withdrawal clearly distinct. |
| UI and service status | The Android library, detail, assignment sheet, create/rename/delete/merge flows, contextual correction actions, and matching `SpeakerService` operations are implemented in this worktree. The service also provides distinct-memory and last-heard summaries. | The experiences below describe the current implementation; the legal/privacy review remains a release prerequisite. |

Primary code references: `features/speaker-recognition/SPEAKER_RECOGNITION.md`; `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/domain/Speaker.kt`; `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/SpeakerService.kt`; `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/kotbase/KotbaseSpeakerService.kt`; `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/speaker/SpeakerMatching.kt`; `composeApp/src/commonMain/kotlin/org/deer/borrowai/SpeakerDetails.kt`; `composeApp/src/commonMain/kotlin/org/deer/borrowai/SpeakerSettings.kt`.

## 4. Information architecture

### Entry points

- **Saved Memories header → Speakers.** Opens the library. Show a count, such as `Speakers · 4`; do not imply that this is a count of all people in the user's life.
- **Settings → Manage speakers.** A stable route for library maintenance and privacy controls. Keep the existing recognition switch, model status, and withdrawal action in the speaker-recognition settings group.
- **Memory detail → Speakers card → Assign / Change.** Opens the assignment sheet for that specific occurrence. Preserve the memory context when the sheet closes.
- **Speaker chips in a memory row.** Continue opening that memory's detail at the selected occurrence. Chips are not library-management controls.

The list-header entry is useful during normal memory browsing; Settings remains discoverable when the memory list is empty or the feature is off. Turning analysis off does not hide an existing library. After consent withdrawal clears speaker data, the library is empty and the app must not imply that any speaker record remains.

### Core navigation

`Saved Memories / Settings → Speaker library → Speaker detail → Memory detail → Assign or change`

The assignment sheet is a contextual modal, not a second library editor: choose a person, create a person, or leave the occurrence unknown. Returning from a successful choice updates the source card in place.

## 5. Speaker library

### Library list

Each row contains:

- Stored speaker color plus the speaker's name.
- `N memories` (distinct memories with a linked occurrence), and `Last heard [date]`; omit the latter when there are no linked occurrences.
- A trailing navigation affordance to the speaker detail. Use a separate overflow menu for Rename, Merge into…, and Delete; do not make a swipe gesture the only way to reach destructive actions.

Default order is most recently used first, matching `SpeakerService.findSpeakers()`. Search matches speaker names case-insensitively. Search is optional and should not introduce a new persistent sort preference. A speaker with no current linked occurrences remains in the list with `No linked memories` rather than silently disappearing.

**Empty library:** Title `Speakers`; message `No named speakers yet`; explanation `Assign a detected voice in a memory to add someone here.` Primary action `Find a memory` returns to Saved Memories. A secondary `Speaker recognition settings` action explains how the feature works and its consent/model prerequisites.

**Loading:** Keep the page title and a small set of non-interactive placeholder rows. Do not show the empty state until the speaker read succeeds.

**Read failure:** Keep the user's navigation context and show `Couldn't load speakers` with `Try again`. Never substitute an empty list for a failed read.

**Search with no results:** `No speakers match “…”` with a clear-search action. Do not display the new-speaker CTA as if a search result were a create form; keep `Add speaker` as the page-level action.

### Speaker detail

Header: stored color, name, and a visible `More` menu containing Rename, Merge into…, Delete. Under it show `Linked in N memories` and an optional last-heard date.

The **Recent memories** section lists linked occurrences newest first. Each row shows memory title (or `Untitled memory`), date, occurrence label such as `Speaker 2`, link status (`Matched automatically` or `Assigned by you`), and a `Play sample` action when audio is available. Tapping the row opens the memory detail at that speaker card. This is the recommended review surface for automatic matches: a person can inspect real examples without a separate, unbounded review inbox.

If there are no linked occurrences, show `No linked memories yet`. If a previously linked memory has been deleted, it should no longer appear after the memory purge and speaker-row cleanup complete. A failed occurrence query gets an inline retry state rather than a false empty state.

### Create speaker

`Add speaker` opens a short form with one field, `Name`, and a helper: `This name labels a voice in your memories. It is not verified identity.` There is no enrollment recording.

- Trim leading/trailing whitespace and require a non-blank name.
- Limit the name to 64 characters. Show a live character counter and enforce the same limit in the service so other callers cannot bypass it.
- Enforce the service's case-insensitive uniqueness rule. If taken, show `A speaker named “…” already exists.` and keep the typed value for correction.
- Disable Save for blank input; preserve input and focus if persistence fails.
- On success, close the form and focus the new row. The name is not saved until the local write succeeds.
- Creating from the assignment sheet creates and assigns to the current occurrence as one user-intent flow. If creation succeeds but linking fails, retain the new speaker and explain that assignment did not finish, with a retry action; never report success-shaped completion.

### Rename

Rename is a one-field dialog prefilled with the current name. Apply the same trim and uniqueness rules as creation; case-only changes are allowed for the same speaker. Cancel leaves all data unchanged. On save failure, keep the dialog and text, show the error, and allow retry. A successful rename updates every chip/card using that identity through the normal speaker change flow; it does not change embeddings, occurrence links, or colors.

### Merge identities

Merge is a deliberate identity operation, not a name edit:

1. From the source speaker's menu choose **Merge into…**.
2. Select a different target from a searchable list. The source cannot be its own target.
3. Review a confirmation: `Merge Alex into Jordan?` Include the number of source-linked memories and explain that all those occurrences will show Jordan, the source identity will be removed, and the original recordings will remain.
4. Confirm to perform one atomic local operation. On success, open the target detail and announce `Merged Alex into Jordan`. On failure, leave source and target unchanged and offer retry.

**Recommended merge semantics:** target name and color win; source occurrence links and embeddings move to the target, preserving occurrence IDs, memory IDs, ordinal, sample times, rejected-speaker history where still meaningful, and manual/automatic link provenance. Keep at most the newest 10 eligible target embeddings across both identities, using the existing embedding-cap rule and model version. Delete the source speaker only after all referenced records are safely moved. Update target last-used from the newest linked occurrence.

Merge cannot be safely undone with the current hard-delete model. Make the confirmation explicit and do not offer a misleading snackbar Undo. The target's stored color remains stable in all memories; the source color is retired.

### Delete identity

Confirm with the speaker name and impact: `Delete Alex?` / `Alex will become unknown in 6 memories. The memories and recordings stay. Alex's stored voiceprints are deleted.` Actions: `Keep speaker` and destructive `Delete speaker`.

On success, affected memory cards and chips become `Speaker N`; the recordings and sample ranges remain playable if their original files exist. The deleted identity's matching voiceprints are removed, so future analysis cannot use them. Do not claim that deleting the identity deletes or edits the underlying memories. No Undo is offered because the voiceprints are deleted; the user may assign the now-unknown occurrences again later.

On persistence failure, keep the confirmation open, show the error inline, and leave the speaker and its links unchanged so the user can retry or cancel. Do not report a successful deletion or dismiss the dialog.

## 6. Assigning and correcting occurrences

### Assignment sheet

Title: `Assign Speaker 2`; supporting context: memory title and date. Show the sample control when audio is available, then a search field and most-recently-used speaker rows. Each row includes name, color, and linked-memory count. Selection applies immediately after confirmation by tap; this is a reversible, local association, not a destructive action. At the bottom place `Create and assign new speaker`.

If the library is empty, state `No named speakers yet` and make `Create and assign new speaker` the primary action. If the read fails, show `Couldn't load speakers` and Retry; do not interpret a read failure as an empty library. If the source memory or occurrence was removed while the sheet was open, close it and announce `This speaker occurrence is no longer available`.

### Unknown occurrence

Show `Speaker 2` with `Not assigned` status, speech duration, sample playback, and **Assign**. Choosing an existing identity changes only this occurrence to `MANUAL`, updates the identity's embedding history/count and last-used time, and refreshes all views. Creating a name from this flow creates the library entry and assigns it to this occurrence.

### Automatically matched occurrence

Show the linked name, a `Matched automatically` badge and a **Change** action. A secondary **Not this person** action opens a small choice: `Leave as unknown` or `Choose another speaker`. Choosing another name makes a manual assignment. Leaving unknown clears the current link and stores that identity in `rejectedSpeakerIds` so a later analysis does not immediately restore the same rejected match.

For review outside the source memory, the speaker detail's Recent memories list identifies auto-matched appearances. A `Check match` action opens the relevant memory and card. Do not present an accuracy score or make the user approve each match before normal use; review is optional, contextual, and reversible.

### Manually assigned occurrence

Show `Assigned by you` and **Change**. Do not show `Not this person`, which implies the app made the decision. Re-analysis must preserve manual assignments, as the existing matching invariant requires. When changing a prior manual assignment, make the new identity manual; choosing `Leave unknown` clears the link and rejects the previous identity for that occurrence.

### Successful change, failure, and undo

After a committed assignment or correction, update the source card and speaker list through the store's change signal. For ordinary reassignment, offer a short `Undo` snackbar that restores the prior link and rejected set if the service can perform a guarded inverse update. If the write fails, keep the current saved label visible, show `Couldn't update speaker` and Retry, and do not show Undo.

For `Not this person`, a lightweight immediate Undo may remove the rejection and restore the prior auto-link. The user must be able to determine which memory and occurrence the action affected. Do not implement undo as a UI-only state change; it must write the inverse to the local store.

## 7. Playback and sample behavior

- Play the sample range from the original memory recording using the existing sample playback target.
- At most one sample plays at a time; starting a different sample stops the first.
- The control label alternates between `Play sample for Alex` and `Stop sample for Alex`; include the sample's memory/occurrence context for repeated names.
- When recording audio is unavailable, show `Audio unavailable` and no play control, but keep the name, status, duration, and assignment actions.
- If playback fails after the control is shown, stop the loading/playing state and show the repository-standard playback error, with a retry where applicable. Never manufacture a sample file or upload audio.
- A library-level recent-memory row may play its linked occurrence sample; there is no independent voice clip saved on the speaker record.

## 8. Consent, disable, and withdrawal

Keep three states distinct in language and effect:

| User action | Analysis | Existing library/links |
|---|---|---|
| Turn recognition off | Stops new and queued analysis | Preserved and still manageable locally |
| Delete one speaker | Recognition setting unchanged | That identity and its linked occurrence links/voiceprints are removed; memories remain |
| Withdraw consent and delete speaker data | Turns analysis off and invalidates consent | All speaker data is cleared; memory speaker analysis statuses are reset by the existing withdrawal flow |

The library header may link to the current consent explanation and Settings. Do not repeat a consent prompt during ordinary management if valid consent already exists. If recognition is unavailable on the device, library management can still work for existing data; disable creation/assignment only if the underlying operation requires an enabled feature, and explain the actual restriction rather than hiding stored records. Product/legal review must approve the consent copy and retention behavior before release; engineering presence of a consent dialog is not a substitute for legal review.

## 9. Accessibility and recovery requirements

- Give every icon-only action a spoken label with the identity and action: `More actions for Alex`, `Play sample for Speaker 2`.
- Expose linked-memory counts and status as text, not color or icon alone. Preserve the `Speaker N` ordinal for unknown speakers.
- Maintain logical focus when opening/closing sheets and dialogs. On successful create, focus the new library row; on deletion, focus the next row or page heading; on cancellation, return focus to the invoking action.
- Ensure names, error messages, and confirmation consequences are announced. A merge confirmation reads both source and target names and the number of affected memories.
- Support large text and narrow screens: wrap long names, keep Save/Delete actions reachable, and allow the assignment list to scroll independently of its header and bottom create action.
- Make loading, empty, search-no-results, unavailable audio, persistence failure, missing occurrence, and retry states distinct. A failed read must never masquerade as an empty library.
- Keep labels and state distinguishable in grayscale and high contrast. Stored color is a cue, not the identity itself.

## 10. Implemented data and service behavior

`SpeakerService` supports listing, creating, renaming, deleting, occurrence reads, feature consent, analysis writes, and the following management operations:

1. Assign or clear one occurrence together with its embedding link, provenance, rejected IDs, embedding cap, and last-used timestamp.
2. Reject an automatic match so a later analysis does not immediately restore it.
3. Merge two speakers transactionally, moving links and eligible embeddings while preserving the target identity and enforcing the 10-embedding cap.
4. Return distinct linked-memory counts and last-heard summaries without one query per speaker. Derive last heard from the newest linked memory's creation time, not the speaker's last-used/analysis timestamp.
5. Return typed errors for duplicate names, missing records, and persistence failures. Voice-vector contents are not logged.

Mutations validate current records, let failures escape the native batch so partial writes roll back, and emit the speaker change signal only after a successful commit. Memory deletion continues to remove associated embeddings and occurrences. Speaker records remain in their separate collection, and audio paths remain relative.

## 11. Implemented behavior

- The same library is reachable from Saved Memories and Settings, including when empty.
- Loading, empty, read failure, search-no-results, unavailable audio, and retry states are distinct.
- Names use trimmed, case-insensitive uniqueness and a 64-character maximum with a visible counter; persistence errors remain actionable.
- Assignment, create-and-assign, correction, and automatic-match rejection affect only the selected occurrence and update after committed writes.
- Speaker detail lists recent linked occurrences and returns to the exact memory card, with sample playback when audio is available.
- Merge confirms impact, preserves the target's name/color and underlying memories, enforces the embedding cap, and is atomic.
- Delete explains that links become unknown and voiceprints are removed while memories/audio remain intact.
- Turning analysis off preserves the library; consent withdrawal retains its existing clear-data behavior.
- Controls expose names and statuses in text, provide spoken labels for sample playback, and use scrollable management/assignment surfaces.

## 12. Open product and legal decisions

| Question | Recommendation | Must be resolved by |
|---|---|---|
| Is the list header link the intended primary library entry point? | Yes; add Settings as a secondary route. | Product |
| Should automatic matches have a separate inbox or require explicit approval? | No approval gate. Use the speaker detail's recent appearances and per-memory correction actions; adding a persistent review queue would require new review state and should be a separate decision. | Product |
| What name length/character limits should creation and rename enforce? | Resolved: 64 characters, visibly counted and enforced by both UI and service. | Product (approved) |
| Should merge be undoable? | No in v1; use clear impact confirmation and an atomic transaction. A reversible merge needs retained source identity/history and a separate data model. | Product / engineering |
| Is this consent and biometric-like data retention model legally sufficient in each launch region? | Do not assume the draft specification answers this. Review consent wording, lawful basis, retention, withdrawal, and deletion with qualified counsel before shipping. | Legal / privacy |
| Should library management remain available when analysis is disabled or the device no longer supports the models? | Yes for retained local data; label the analysis state separately. | Product |

## 13. Related sources

- [Speaker recognition specification](../speaker-recognition/speaker-recognition.html)
- [Speaker recognition visual guide](../speaker-recognition/visual-guide.html)
- `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/domain/Speaker.kt`
- `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/SpeakerService.kt`
- `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/kotbase/KotbaseSpeakerService.kt`
- `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/service/speaker/SpeakerMatching.kt`
- `composeApp/src/commonMain/kotlin/org/deer/borrowai/SpeakerDetails.kt`
- `composeApp/src/commonMain/kotlin/org/deer/borrowai/SpeakerSettings.kt`
