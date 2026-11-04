package org.deer.borrowai.data.service.recording

import android.media.MediaMetadataRetriever

/** Reads a recording's length with [MediaMetadataRetriever]. A file it cannot read has no duration. */
class AndroidAudioDurationReader : AudioDurationReader {
    override fun durationMillis(absolutePath: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
        } catch (exception: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }
}
