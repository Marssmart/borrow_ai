# Search and filter

Status: Phases 1 to 3 built on Android. Phase 1's exit criteria in section 12 are not yet met: the benchmark (Q-7) and the device checks are open. Phase 3's device checks are open (Q-8). Phase 4 is not built (draft v0.1)
Visual guide: [visual-guide.html](./visual-guide.html)
Owner: BorrowAI native team

---

## 1. Summary

The saved memories list cannot be searched or filtered. It shows every active memory in one scrolling column (`SavedMemoriesScreen.kt`, `MemoryListScreen`, `verticalScroll` at line 246). A user who remembers a phrase from a voice note has to scroll to find it.

This design adds:

1. A search field above the list. It matches a word or phrase in the title, summary, or transcript.
2. Category filter chips. A user can select one or more categories.
3. Clear empty states: no results, and a library with no memories at all.
4. Keyboard and back behaviour that a user can predict.

Decision: search runs in memory over the list that `getState()` already holds, not in a Couchbase full-text index. Kotbase does support full-text indexes (§6.2), so the index stays as the upgrade path if the in-memory search becomes too slow.

The UI is Android only and uses Material 3. The matching rules are pure Kotlin in `commonMain`.

---

## 2. Goals and non-goals

### Goals

- Find a memory by any word or phrase that appears in its title, summary, or transcript.
- Filter by category, with one or more categories selected.
- Combine search and filters, and say how many memories match.
- Keep the list responsive with several hundred memories.
- Give clear messages when nothing matches, and when the library is empty.

### Non-goals (v1)

- Date, duration, or speaker filters. Speaker names belong to the speaker recognition feature.
- Relevance ranking. Results keep the newest-first order of the list.
- Highlighting matched words inside text. A follow-up.
- Saved searches or search history.
- Accent-insensitive matching (Q-1).
- Searching audio. Only text is matched.
- iOS UI and behaviour. Not designed here.

---

## 3. Terms

| Term | Meaning |
|---|---|
| Query | The text in the search field, trimmed, with spaces collapsed. |
| Term | One word of the query. Terms are split on whitespace. |
| Searchable text | A memory's title, summary, and transcript, read together. |
| Category filter | The set of selected category ids. Matches memories that have at least one selected category. |
| Result set | The memories that match the query and the category filter, newest first. |
| Pending text | A memory whose transcript is not ready yet. It has no text to match. |

---

## 4. User stories and acceptance criteria

### US-1: Search the list

As a user, I want to find a memory by a word I remember from it.

- AC-1.1: A search field sits above the list, under the "Saved memories" heading. Its label is "Search memories", and it has a visible text field, not only a placeholder.
- AC-1.2: The result set updates 150 ms after the last keystroke.
- AC-1.3: Matching is case-insensitive. Every term must appear in at least one of the title, summary, or transcript. A term may match different fields.
- AC-1.4: A query longer than 100 characters is cut at 100, and the field shows that it was cut.
- AC-1.5: An empty query shows every memory, as the list does today.
- AC-1.6: A memory with no text yet (a transcript still being made) never matches. The no-results message says this (US-3).
- AC-1.7: A partial word matches. "meet" matches "meeting". Matching is by substring, not by whole word.

### US-2: Filter by category

- AC-2.1: One filter chip appears for each category used by at least one active memory. Chips follow the category id order, which is creation order.
- AC-2.2: A chip shows the category name and its count, for example "Work 4". It is a `FilterChip` with the selected state.
- AC-2.3: Several chips can be selected. A memory matches when it has at least one of the selected categories (OR).
- AC-2.4: A **Clear filters** action appears when any chip is selected, and clears them.
- AC-2.5: The category filter and the search combine with AND. A memory must match both.

### US-3: Empty and no-result states

- AC-3.1: When a search or filter leaves no results, the list shows "No memories match “<query>”" (or "No memories in these categories" when only chips are selected). Below it: a hint, "Search covers titles, summaries, and transcripts. Memories still being transcribed are not searched yet." The same hint is used for both messages. §7.4 limits chips to categories in use, so a chip selection always matches a memory today, and the chips-only message is not reached.
- AC-3.2: The no-results state has **Clear search** (when a query exists) and **Clear filters** (when chips are selected) buttons.
- AC-3.3: When the library has no memories at all, the empty state from the delete design is shown ([Delete memories](../delete-memories/delete-memories.html), US-5), not the no-results state.

### US-4: Keyboard and back

- AC-4.1: The keyboard shows a **Search** action. Pressing it hides the keyboard and keeps the results.
- AC-4.2: Scrolling the list hides the keyboard.
- AC-4.3: A clear button, labelled "Clear search", empties the query and keeps the focus in the field.
- AC-4.4: On a hardware keyboard, Enter runs the search and Escape clears the query.
- AC-4.5: System back on the Memories tab first hides the keyboard, then clears a non-empty query. Only then does back do what it does today. Back on the detail view keeps its current behaviour (`registerMemoryDetailBack`).

### US-5: Results while a memory is open

- AC-5.1: While the detail view is open, the result set does not change, even if a memory is edited or deleted there.
- AC-5.2: When the user returns to the list, the result set is recalculated, and the search field and chips keep their values.

---

## 5. Flow

1. The user types in the search field.
2. After 150 ms without a keystroke, the query is applied on a background dispatcher.
3. The result set is the memories the list shows that match the query and the selected categories, newest first. A memory hidden by a pending delete (its Undo window, or its write in progress) is left out.
4. The list shows the result set, with a count line above it: "3 memories" or "Showing 3 of 42".
5. Selecting or clearing a chip recalculates the result set at once, without the 150 ms delay.

---

## 6. Data and service changes

### 6.1 Current data path

- `KotbaseMemoryService` publishes every active memory, with its full text, into one `StateFlow` (`publishMemories`, `KotbaseMemoryService.kt`, line 313). `StatefulMemoryService.getState()` returns that flow (`StatefulMemoryService.kt`, line 14). The data is already in memory, so searching it costs no extra storage.
- `MemoryService.findCategories()` returns the category names (`MemoryService.kt`, line 117). `SavedMemoriesStateHolder.refreshCategoryNames` already uses it (`SavedMemoriesStateHolder.kt`, line 155).
- `SavedMemoriesStateHolder.updateMemories` already sorts by creation time, newest first (`SavedMemoriesStateHolder.kt`, line 144).

### 6.2 Options for search

Kotbase 3.2.4-1.2.0 (the version pinned in `gradle/libs.versions.toml`, line 9) exposes a full-text index in its common API. Checked in the library's sources jar (`couchbase-lite-android-3.2.4-1.2.0-sources.jar`, `commonMain/kotbase/`):

- `IndexBuilder.fullTextIndex(vararg items: FullTextIndexItem)`, with `FullTextIndexItem.property(name)`.
- `FullTextIndexConfiguration`, with `ignoreAccents` and `language` options.
- `FullTextFunction.match(indexName, query)` and `FullTextFunction.rank(indexName)`.

So a full-text index is possible without a new dependency. The trade-off is below.

| Aspect | In-memory filtering (chosen for v1) | Full-text index (upgrade path) |
|---|---|---|
| Data | Uses the memories already held in `getState()`. | An index in the database. Built when the database opens, and updated on every write, including each summary and category write from the AI jobs. |
| Matching | Substring. "meet" matches "meeting". | Token based. Partial words need prefix syntax. Stemming depends on the language setting. |
| Accents | Needs its own folding (Q-1). | `ignoreAccents` option. |
| Ranking | Newest first, as the list is. | `rank` gives relevance order. |
| Query safety | Plain text, no syntax. | User text must be quoted or escaped before `match`, because the query has its own syntax. |
| Threading | Pure Kotlin on `Dispatchers.Default`. | The query runs in the database, so the service call is suspending. |
| Testing | commonTest, no database. | Needs instrumented tests (`KotbaseMemoryServiceTest`). |
| Scale | Good to a few thousand memories (target in §10). | Better for large libraries. |

Decision: in-memory for v1. Voice notes are a small corpus, and the data is already in memory. Substring matching also finds partial words, which full-text prefix search does not do by default. The index is kept as the upgrade path (phase 4).

### 6.3 Switch criteria for the full-text index

Move to the index only if one of these is true on the reference mid-range device:

- The 95th percentile query time is above 50 ms with 1,000 memories, after the optimisations in §6.4.
- Users ask for ranked results.

### 6.4 Performance plan for in-memory search

- Build a lowercase copy of each memory's searchable text once, and reuse it until the memory changes. The cache is keyed by memory id. A copy is reused only while the title, summary, and transcript still equal the values it was made from. The comparison is by value, not by hash, so a hash collision cannot return stale text. Without this, each keystroke allocates a new lowercase copy of every transcript.
- Run the filter on `Dispatchers.Default` (`flowOn`).
- Keep the 150 ms delay for typed text only. Chips apply at once.
- Measure with a generated library of 500 and 1,000 memories (instrumented test, §11).

### 6.5 Service and file changes

| File | Change |
|---|---|
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/data/domain/MemorySearch.kt` (new) | Pure matcher: normalise the query, split into terms, match terms against title, summary, and transcript, and filter by categories. Holds the cache from §6.4. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/SavedMemoriesStateHolder.kt` | Hold the query and the selected category ids in `SavedMemoriesUiState`. Derive the result set from `getState()`, the query (debounced by 150 ms for typing), and the selection. Freeze the result set while the detail view is open (AC-5.1). Replace `memoriesScrollState: ScrollState` with `memoriesListState: LazyListState` (renamed, because the old name held a `ScrollState`), so the list can be a `LazyColumn`. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/SavedMemoriesScreen.kt` | Replace the `verticalScroll` column in `MemoryListScreen` (line 246) with a `LazyColumn`. Add the search field, the chip row (`LazyRow` of `FilterChip`), the count line, and the no-results state. Hide the keyboard on list scroll. Register back handling for the query. |
| `composeApp/src/commonMain/kotlin/org/deer/borrowai/MemorySearchControls.kt` (new) | The search field (with the Clear search button and the counter), the count line, and the drawn search and close icons. |
| `composeApp/src/commonMain/composeResources/values/memory_strings.xml` | New strings: search label, placeholder, count lines, chip labels, no-results texts, hints, and button labels. |
| `composeApp/src/commonTest/kotlin/org/deer/borrowai/data/domain/MemorySearchTest.kt` (new) | Unit tests for the matcher (§11). |

No change to `MemoryService`, `KotbaseMemoryService`, or the schema. Phase 4 (only if §6.3 is met) adds the index in `KotbaseMemoryService.open` and a query method on `MemoryService`.

Why `LazyColumn`? The list is a `verticalScroll` column, so every card is composed at once. Filtering does not fix that. The same change is needed for the 500-memory target in the speaker recognition design ([Speaker recognition](../speaker-recognition/speaker-recognition.html), AC-1.5).

---

## 7. UI (Android, Material 3)

### 7.1 Layout

From top to bottom, inside the Memories page:

1. The "Saved memories" heading, as today.
2. The search field. A Material 3 `OutlinedTextField` with a leading search icon, a trailing **Clear search** button (shown only when the query is not empty), the label "Search memories", and the **Search** IME action.
3. The filter row. A `LazyRow` of `FilterChip`s, one per category in use, each with its count. **Clear filters** appears at the end of the row when a chip is selected.
4. The count line, for example "3 memories" or "Showing 3 of 42".
5. The list of cards, or a state from §7.3.

The filter row hides when the library has no categories in use.

### 7.2 Search field behaviour

- A single line. Typing updates the result set after 150 ms.
- The IME action **Search** hides the keyboard (AC-4.1).
- The field keeps its text when the user opens a memory and comes back (AC-5.2).
- Length is capped at 100 characters, with the counter shown only near the limit.

### 7.3 States

| State | What the user sees |
|---|---|
| Library has memories, no query or filter | Full list, count "42 memories". |
| Query or filter, with results | Filtered list, count "Showing 3 of 42". |
| Query or filter, no results | No-results message, hint, and **Clear search** and/or **Clear filters**. |
| Library empty | Empty state from [Delete memories](../delete-memories/delete-memories.html), US-5. No search field is needed, so it is hidden. |
| Memory still being transcribed | Its card shows the pending state, as today. It does not match a query. |

### 7.4 Chips

- Each chip is a `FilterChip` with the selected state, the category name, and its count, for example "Work 4".
- Chips use the app's colour roles: `primaryContainer` (#C9E5E7) with `onPrimaryContainer` (#123B43) when selected.
- The chip row scrolls horizontally. The chip's touch target is at least 48 dp (`minimumInteractiveComponentSize`).
- Chip counts and the chip list use the memories the list shows, so a memory hidden by a pending delete is not counted. A selected chip whose category no memory uses any longer filters nothing, and it is not shown as active.

---

## 8. Accessibility

- The search field has a label, so TalkBack does not depend on the placeholder.
- The clear button is labelled "Clear search". The **Clear filters** button is labelled "Clear category filters".
- Chips are toggles, with the selected state read by TalkBack ("selected" or "not selected"), and the count is part of the label.
- The count line updates after the 150 ms delay. It is a polite live region (the same pattern as `BorrowCaptureScreen.kt`, line 261). It is not announced on each keystroke.
- Keyboard: **Search** runs the search. Scrolling hides the keyboard. On a hardware keyboard, Enter runs the search and Escape clears the query.
- Back: the first back press hides the keyboard, then clears the query (AC-4.5). The system back behaviour is then unchanged.
- Colour is never the only cue. The selected chip has a check icon as well as the colour change.
- Contrast: the selected chip text, #123B43 on #C9E5E7, is about 9:1.

---

## 9. Platform split

| Layer | Location | Contents |
|---|---|---|
| Matching rules | `commonMain` | `MemorySearch`, the query normalising, the cache |
| State | `commonMain` | Query, selection, derived result set |
| UI | `commonMain`, Compose Material 3 | Field, chips, count line, no-results state, `LazyColumn` |
| Index (phase 4 only) | `commonMain`, Kotbase | `IndexBuilder`, `FullTextFunction` |
| iOS | Not designed here | Shared code compiles, but iOS screens and behaviour are out of scope |

---

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Slow typing on a large library | Janky input | The cache in §6.4, filtering off the main thread, the 150 ms delay, and a 1,000-memory benchmark. |
| Matching a half-written word | Noisy results | Substring matching is intended (AC-1.7), and the delay keeps results steady. |
| Results change under the user while reading | Confusion | Results freeze while the detail view is open (AC-5.1). |
| A pending transcript looks like a missing match | User thinks the memory is lost | The no-results hint explains it (AC-3.1). |
| Accents do not match | Missed results | Known gap. Q-1 decides whether to add folding. |
| The list is still a `verticalScroll` column | Slow with several hundred memories | `LazyColumn` in the same change (§6.5). |

---

## 11. Open questions

| # | Question | Decision |
|---|---|---|
| Q-1 | Should matching ignore accents in v1? | **Open.** Default: no. Needs a folding table in common code, or the full-text index. |
| Q-2 | Should the multi-select filter use AND instead of OR? | **Decided: OR** (AC-2.3). Revisit if users ask for AND. |
| Q-3 | Should the chips show counts? | **Decided: yes** (AC-2.2). |
| Q-4 | Should results show which field matched? | **Open.** A follow-up, not v1. |
| Q-5 | Should results be ordered by relevance? | **Decided: no** for v1. Newest first. |
| Q-6 | Should the list become a `LazyColumn` before or with search? | **Decided: with search** (§6.5). |
| Q-7 | Has the 1,000-memory benchmark (§6.3, §11) been run on a mid-range device? | **Open.** Not run in the build session, so the phase 4 decision is still open. Record the 95th percentile here before deciding on phase 4. |
| Q-8 | Do the phase 3 keyboard and back rules work on a device? | **Open.** Built, and the rules are unit-tested, but not run on a device. Check on Android, with TalkBack and a hardware keyboard: the Search key, scrolling, Enter, Escape, back with the keyboard open and with a query, the IME inset (`WindowInsets.ime`) reporting the keyboard, and the no-results layout. The walk-through is in §13 (Manual). |

Test and measurement:

- `MemorySearchTest` (commonTest): case-insensitivity, substring matches, every term required, terms may match different fields, a blank query shows everything, a 100-character cut, a pending memory never matches, category OR, category and query AND, newest-first order.
- Instrumented benchmark (androidInstrumentedTest): time the matcher on 500 and 1,000 generated memories with realistic transcript lengths, on a mid-range device. Record the 95th percentile in this document.

---

## 12. Phased rollout

| Phase | Scope | Exit criteria |
|---|---|---|
| P1 Search | `LazyColumn`, search field, matcher, debounce, count line, clear button | US-1 met. `MemorySearchTest` green. Benchmark recorded. |
| P2 Category filter | Chip row, OR filter, **Clear filters**, combined AND | US-2 met. |
| P3 States and keyboard | No-results state, hint, **Clear search**, IME action, Escape, back handling, freeze while detail is open | US-3, US-4, US-5 met on Android. |
| P4 Full-text index (only if §6.3 is met) | `IndexBuilder.fullTextIndex`, query method, escaping, instrumented tests | Benchmark shows the target is met. Results match the in-memory matcher, apart from ordering. |

---

## 13. Test plan

### Unit (commonTest)

- `MemorySearchTest` (`composeApp/src/commonTest/kotlin/org/deer/borrowai/data/domain/MemorySearchTest.kt`, new file), covering §11.
- `SavedMemoriesStateHolder` (commonTest, with a fake `MemoryService` such as `composeApp/src/commonTest/kotlin/org/deer/borrowai/data/service/FakeMemoryService.kt`): the 150 ms delay for typing, no delay for chips, the frozen result set while the detail view is open, and the recalculation on return.

### Instrumented (androidInstrumentedTest)

- A benchmark with 500 and 1,000 memories, and a check that the list is a `LazyColumn` (scroll 500 items without dropped frames on a device).

### Manual (device)

- Search for a word from a transcript, a summary, and a title. Confirm each finds the memory.
- Search for part of a word. Confirm it matches.
- Select two chips. Confirm OR. Add a query. Confirm AND.
- Search for something that does not exist, and check the hint and both buttons.
- TalkBack walk-through of the field, the clear button, the chips, and the count line.
- Hardware keyboard: Enter and Escape.
- Back with a query open, then back with no query.

---

## 14. Definition of done

- [ ] Search and filter rules match §4 and §6.5.
- [ ] The 1,000-memory benchmark is recorded in this document (§11).
- [ ] The list is a `LazyColumn`.
- [ ] TalkBack walk-through passes on Android.
- [ ] `visual-guide.html` matches the built UI, or the differences are noted.
- [ ] The full-text index is not added unless §6.3 is met.
