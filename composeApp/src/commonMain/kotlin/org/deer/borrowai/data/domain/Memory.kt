package org.deer.borrowai.data.domain

data class Memory(
    val id: String,
    val createdAtEpochMillis: Long,
    val title: String,
    val summary: String,
    val transcription: String,
)

data class MemoryDraft(
    val title: String,
    val summary: String,
    val transcription: String,
) {
    init {
        require(title.isNotBlank()) { "A memory title cannot be blank." }
    }
}
