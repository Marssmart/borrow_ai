package org.deer.borrowai.data.service.recording

/** Reads how long a finished recording is, in milliseconds. Returns null when the duration cannot be read. */
fun interface AudioDurationReader {
    fun durationMillis(absolutePath: String): Long?

    companion object {
        /** Reads nothing, so recordings saved with it have no stored duration. */
        val None: AudioDurationReader = AudioDurationReader { null }
    }
}
