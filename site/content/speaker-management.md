# Speaker management

Speaker management is available on Android from **Speakers** in Saved Memories or **Manage speakers** in Settings. It provides a local library of names linked to detected voice occurrences in memories. The library remains available when recognition is turned off; withdrawing consent continues to clear speaker data using the existing privacy flow.

## Manage identities

The library supports searching, adding, renaming, merging, and deleting speakers. Names are trimmed, case-insensitively unique, and limited to 64 characters. The create and rename fields show a live character counter. Speaker detail shows linked-memory counts, last-heard information, and recent occurrences, including whether each was matched automatically or assigned by the user.

Merge and delete require confirmation. Merge keeps the target speaker's name and color and moves eligible links and voiceprints. Delete removes that identity's links and voiceprints, leaving the memories and their recordings intact. While deletion is pending, the dialog cannot be dismissed and its actions are disabled; if deletion fails to persist, it stays open and shows an inline error so the user can retry or cancel.

## Assign and correct

From a speaker occurrence in memory detail, choose **Assign** or **Change** to open a contextual sheet. You can assign an existing speaker, create and assign a new one, or correct an automatic match. **Not this person** lets you leave the occurrence unknown or choose another speaker. These actions change only the selected occurrence; the original memory and recording remain unchanged. Sample playback uses the original recording when it is available.

Automatic matches are suggestions, not verified identities. Speaker detail offers an optional way to review recent appearances and return to the corresponding memory. There is no separate review inbox or accuracy score.

## Documentation

- [Speaker management design and implementation notes](features/speaker-management/speaker-management.html)
- [Speaker management visual guide](features/speaker-management/visual-guide.html)
- [Speaker recognition and privacy](models-and-privacy.html#speaker-recognition)
