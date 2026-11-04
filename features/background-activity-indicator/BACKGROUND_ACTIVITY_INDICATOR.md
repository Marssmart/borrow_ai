# Background activity indicator

A small pill at the top of the screen that shows the work the app is doing in the background.

## What it shows

The pill sits at the top center of every page, just below the status bar. It has a spinner and the names of the running activities, separated by a dot, for example **Transcribing · Summarizing**.

- It names up to two activities. Any further activities are shown as a count, such as **Transcribing · Summarizing +1**.
- It fades in when work starts and fades out when the last activity ends. The last names stay on screen during the fade-out so the text does not go blank.

## When it is visible

The pill is visible only when both are true:

1. The **Background activity indicator** setting is on.
2. At least one tracked activity is running.

Idle periodic checks do not show the pill. An activity is shown only while a unit of its work is actually running.

## Activities it tracks

Activities are listed in this order. The label is what the pill shows.

| Label | Activity | Where it is tracked |
|-------|----------|---------------------|
| Downloading models | Model download and loading, from the model setup phase | Model setup state |
| Transcribing | Batch transcription of a finished recording | `TranscriptionWorkRunner` |
| Analyzing speakers | Speaker analysis after a transcription | `SpeakerAnalysisRunner` |
| Summarizing | Summary generation and retries | `MemorySummaryRecoveryJob` |
| Categorizing | Category generation and retries | `MemoryCategorizationRecoveryJob` |
| Cleaning up | Removing audio and records of deleted memories, and creating memories for orphaned recordings | `DeletedMemoryPurger`, `RecordingReconciler` |

The jobs wrap each unit of work in `BackgroundActivityTracker.track(...)`. An activity stays active until its last unit of work ends, even if several run at once.

Cleanup is tracked only for a memory that is actually being removed or recreated. When only a few items need cleaning, the pill can flash briefly and then disappear. This is expected.

## Turning it on or off

1. Open **Settings** from the bottom bar.
2. Turn the **Background activity indicator** switch on or off.

The switch is on by default. Your choice is saved in the app's local settings collection under the key `showBackgroundActivityIndicator`, so it persists across launches. Turning it off hides the pill, but the background work still runs.
