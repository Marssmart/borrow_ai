# How it works

## The three screens

The app has three pages in a horizontal pager. The bottom bar switches between them, and you can also swipe.

| Page | Nav label | Purpose |
|------|-----------|---------|
| Capture | Record | Record a memory. Shows the live transcript while you talk. |
| Saved memories | Memories | List of saved memories with their summaries, categories, and playback. |
| Settings | Settings | App settings. Includes the background activity indicator switch and the GPU acceleration switch. |

On the Record page, the hint reads **TAP/HOLD TO RECORD**. While recording it reads **TAP TO STOP** (tap mode) or **RELEASE TO STOP** (hold mode). A hint at the bottom reads **SWIPE LEFT FOR SAVED MEMORIES**. Strings come from `composeApp/src/commonMain/composeResources/values/capture.xml`.

## Recording a memory

1. You tap or hold the record control. The app records audio as M4A.
2. While recording, a live transcriber shows text on a 16 kHz path using a small speech model.
3. When you stop, the finished file is saved and a memory is created. Its audio is stored with a relative path such as `recordings/<id>.m4a`.
4. Unfinished captures use a temporary file prefix. They are not treated as saved memories.

Recording uses the microphone permission. If it is denied, the app shows a rationale and an action to open system settings.

## What runs after you stop

A saved memory moves through several pipelines. Each has its own status and attempt counter on the memory, so a failure in one does not block the others.

| Step | What it produces | Notes |
|------|------------------|-------|
| Batch transcription | The full transcript | Uses the tier's voice model on the finished file. |
| Summary | A summary | Uses the on-device LLM (Android only in this build; see [Platforms](platforms.html)). A memory gives up after a fixed number of attempts. |
| Categorization | Category chips | Each category is scored in its own request. Categories come from a master table. |
| Speaker analysis | Speaker data | Off by default. See [the feature pages](features/index.html). |

Failed steps are retried by recovery jobs. See [Architecture](architecture.html#background-jobs) for the job list. While these steps run, a small pill at the top of the screen names the activities in progress. See [Background activity indicator](features/background-activity-indicator/background-activity-indicator.html).

## Saved memories

Each memory has a title, summary, transcription, creation time, and optional categories. The summary and categories fill in after recording, so they can be empty at first. The list shows categories as chips. You can play back the recording. Deleting a memory is a soft delete: it is hidden at once, and a purge job removes the record and its audio later.

## Model downloads

Model downloads are held until the device is on an unmetered connection, or until you consent to mobile data for that run (the **Download over mobile data** option). Model files are pinned by URL, revision, SHA-256, and size. See [Models and privacy](models-and-privacy.html).
