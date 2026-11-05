package org.deer.borrowai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.MAX_SPEAKER_NAME_LENGTH
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.SPEAKER_ANALYSIS_VERSION
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.domain.SpeakerOccurrenceLink
import org.deer.borrowai.data.service.FakeSpeakerService
import org.deer.borrowai.data.service.SpeakerError

class SpeakerManagementServiceTest {
    @Test
    fun nameLimitIsSharedByCreationAndRename() = runTest {
        val service = FakeSpeakerService(enabled = true)
        val accepted = "a".repeat(MAX_SPEAKER_NAME_LENGTH)
        val speaker = service.createSpeaker(accepted).getOrNull()!!

        assertEquals(accepted, speaker.name)
        assertIs<SpeakerError.InvalidInput>(service.createSpeaker("$accepted!").leftOrNull())
        assertIs<SpeakerError.InvalidInput>(service.renameSpeaker(speaker.id, "$accepted!").leftOrNull())
    }

    @Test
    fun assignmentUpdatesLinkEmbeddingSummaryAndSupportsGuardedUndo() = runTest {
        val service = FakeSpeakerService(enabled = true)
        val source = service.createSpeaker("Source").getOrNull()!!
        val target = service.createSpeaker("Target").getOrNull()!!
        service.replaceOccurrences(
            memoryId = "memory-1",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(draft(1, source.id)),
        )
        val original = service.findOccurrences("memory-1").getOrNull()!!.single()
        val previous = SpeakerOccurrenceLink(original.id, original.speakerId, original.link, original.rejectedSpeakerIds)

        assertTrue(service.assignOccurrence(original.id, target.id).isRight())

        val assigned = service.findOccurrences("memory-1").getOrNull()!!.single()
        assertEquals(target.id, assigned.speakerId)
        assertEquals(SpeakerLink.MANUAL, assigned.link)
        assertEquals(target.id, service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!.single().speakerId)
        val summaries = service.findSpeakerSummaries(mapOf("memory-1" to 123L)).getOrNull()!!
        assertEquals(0, summaries[source.id]?.linkedMemoryCount)
        assertEquals(1, summaries[target.id]?.linkedMemoryCount)
        assertEquals(123L, summaries[target.id]?.lastHeardAtEpochMillis)

        val expected = SpeakerOccurrenceLink(assigned.id, assigned.speakerId, assigned.link, assigned.rejectedSpeakerIds)
        assertTrue(service.restoreOccurrenceLink(expected, previous).isRight())
        val restored = service.findOccurrences("memory-1").getOrNull()!!.single()
        assertEquals(SpeakerLink.AUTO, restored.link)
        assertEquals(source.id, restored.speakerId)
    }

    @Test
    fun rejectedAutomaticMatchStaysUnknownAndDoesNotKeepALinkedEmbedding() = runTest {
        val service = FakeSpeakerService(enabled = true)
        val source = service.createSpeaker("Source").getOrNull()!!
        service.replaceOccurrences(
            memoryId = "memory-1",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(draft(1, source.id)),
        )
        val occurrence = service.findOccurrences("memory-1").getOrNull()!!.single()

        assertTrue(service.rejectAutomaticMatch(occurrence.id).isRight())

        val rejected = service.findOccurrences("memory-1").getOrNull()!!.single()
        assertNull(rejected.speakerId)
        assertEquals(SpeakerLink.NONE, rejected.link)
        assertTrue(source.id in rejected.rejectedSpeakerIds)
        assertTrue(service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!.isEmpty())
    }

    @Test
    fun mergeKeepsTargetIdentityAndMovesLinksAndEmbeddingHistory() = runTest {
        val service = FakeSpeakerService(enabled = true)
        val source = service.createSpeaker("Source").getOrNull()!!
        val target = service.createSpeaker("Target").getOrNull()!!
        service.replaceOccurrences(
            memoryId = "memory-1",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(draft(1, source.id)),
        )
        service.replaceOccurrences(
            memoryId = "memory-2",
            modelVersion = SPEAKER_ANALYSIS_VERSION,
            drafts = listOf(draft(1, target.id)),
        )

        assertTrue(service.mergeSpeakers(source.id, target.id).isRight())

        assertIs<SpeakerError.NotFound>(service.findSpeaker(source.id).leftOrNull())
        val retainedTarget = service.findSpeaker(target.id).getOrNull()!!
        assertEquals(target.name, retainedTarget.name)
        assertEquals(target.colorIndex, retainedTarget.colorIndex)
        assertEquals(2, retainedTarget.embeddingCount)
        assertEquals(setOf(target.id), service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!
            .mapNotNull { it.speakerId }.toSet())
        assertEquals(
            2,
            service.findSpeakerSummaries(mapOf("memory-1" to 1L, "memory-2" to 2L))
                .getOrNull()!![target.id]?.linkedMemoryCount,
        )
        assertEquals(target.id, service.findOccurrences("memory-1").getOrNull()!!.single().speakerId)
    }

    private fun draft(ordinal: Int, speakerId: String?) = OccurrenceDraft(
        ordinal = ordinal,
        speakerId = speakerId,
        link = if (speakerId == null) SpeakerLink.NONE else SpeakerLink.AUTO,
        totalSpeechMs = 1_000L,
        sampleStartMs = ordinal * 2_000L,
        sampleEndMs = ordinal * 2_000L + 1_000L,
        centroid = floatArrayOf(1f, 0f, 0f),
    )
}
