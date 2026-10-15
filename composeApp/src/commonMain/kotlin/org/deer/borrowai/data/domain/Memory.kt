package org.deer.borrowai.data.domain

/**
 * A saved note.
 *
 * [recordingPath] is the location of the raw recording relative to the app-owned recordings
 * directory (for example `recordings/abc.m4a`). It is never an absolute path: iOS changes the
 * app container path across reinstalls and updates, so the platform layer resolves the relative
 * path at playback time.
 */
data class Memory(
    val id: String,
    val createdAtEpochMillis: Long,
    val title: String,
    val summary: String,
    val transcription: String,
    val recordingPath: String? = null,
) {
    init {
        recordingPath?.let(::requireRelativeRecordingPath)
    }
}

data class MemoryDraft(
    val title: String,
    val summary: String,
    val transcription: String,
    val recordingPath: String? = null,
) {
    init {
        recordingPath?.let(::requireRelativeRecordingPath)
    }
}

private fun requireRelativeRecordingPath(path: String) {
    require(path.isNotBlank()) { "A recording path cannot be blank." }
    require(!path.startsWith("/") && !path.startsWith("\\")) {
        "A recording path must be relative to the app recordings directory."
    }
    require(!path.contains("://")) { "A recording path cannot be a URI." }
    require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "A recording path cannot start with a drive letter." }
    require(path.split('/', '\\').none { it == ".." }) { "A recording path cannot contain parent segments." }
}
