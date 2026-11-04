package org.deer.borrowai.data.domain

/** The fields the editor saves. A blank [summary] asks for a new one. [categoryIds] is the whole list. */
data class MemoryEdit(
    val title: String,
    val summary: String,
    val transcription: String,
    val categoryIds: List<Int>,
)

const val MEMORY_TITLE_MAX_LENGTH = 80
const val MEMORY_SUMMARY_MAX_LENGTH = 4_000
const val MEMORY_TRANSCRIPTION_MAX_LENGTH = 50_000

/** A transcript can be typed in or changed only once it is done or has failed. */
fun TranscriptionStatus.isTranscriptEditable(): Boolean = this == TranscriptionStatus.DONE || this == TranscriptionStatus.FAILED

/**
 * The first rule [edit] breaks, or null. The transcript rules apply only when [transcriptionStatus] allows an edit.
 */
fun memoryEditViolation(edit: MemoryEdit, transcriptionStatus: TranscriptionStatus): String? = when {
    edit.title.length > MEMORY_TITLE_MAX_LENGTH ->
        "A title can be at most $MEMORY_TITLE_MAX_LENGTH characters."
    edit.summary.length > MEMORY_SUMMARY_MAX_LENGTH ->
        "A summary can be at most $MEMORY_SUMMARY_MAX_LENGTH characters."
    transcriptionStatus.isTranscriptEditable() && edit.transcription.isBlank() ->
        "The transcript can't be empty."
    transcriptionStatus.isTranscriptEditable() && edit.transcription.length > MEMORY_TRANSCRIPTION_MAX_LENGTH ->
        "A transcript can be at most $MEMORY_TRANSCRIPTION_MAX_LENGTH characters."
    else -> categoryIdsViolation(edit.categoryIds)
}

/**
 * The memory with [edit] applied. Only changed fields are touched. A changed summary or category list sets its
 * marker. A changed transcript keeps the summary and the categories as they are.
 */
fun Memory.withEdit(edit: MemoryEdit): Memory {
    val titled = copy(title = edit.title)
    val transcribed = if (edit.transcription == transcription) titled else titled.withTranscription(edit.transcription)
    val summarized = if (edit.summary == summary) transcribed else transcribed.withUserSummary(edit.summary)
    return summarized.withUserCategories(edit.categoryIds)
}

/** The edit that keeps every field of this memory as it is. An editor starts from it and changes only what it shows. */
fun Memory.currentEdit(): MemoryEdit = MemoryEdit(title, summary, transcription, categories)
