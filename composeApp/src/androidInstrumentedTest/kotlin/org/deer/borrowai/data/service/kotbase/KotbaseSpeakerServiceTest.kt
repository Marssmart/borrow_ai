package org.deer.borrowai.data.service.kotbase

import kotbase.DataSource
import kotbase.Database
import kotbase.Expression
import kotbase.Meta
import kotbase.MutableDocument
import kotbase.QueryBuilder
import kotbase.SelectResult
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.domain.MAX_EMBEDDINGS_PER_SPEAKER
import org.deer.borrowai.data.domain.OccurrenceDraft
import org.deer.borrowai.data.domain.SPEAKER_ANALYSIS_VERSION
import org.deer.borrowai.data.domain.SpeakerLink
import org.deer.borrowai.data.service.SpeakerError

class KotbaseSpeakerServiceTest {
    private var database: Database? = null
    private val databaseName = "speaker-test-${System.nanoTime()}"
    private var now = 1_800_000_000_000L

    @BeforeTest
    fun setUp() {
        database = Database(databaseName)
    }

    @AfterTest
    fun tearDown() {
        database?.delete()
        database = null
    }

    /** Opens the store with current consent and analysis on, unless [enabled] is false. */
    private suspend fun openService(enabled: Boolean = true): KotbaseSpeakerService {
        val service = KotbaseSpeakerService.open(
            database = requireNotNull(database),
            epochClock = EpochClock { now },
        ).getOrNull()!!
        if (enabled) service.acceptConsent()
        return service
    }

    private fun draft(speakerId: String?, startMs: Long = 0L) = OccurrenceDraft(
        ordinal = 1,
        speakerId = speakerId,
        link = if (speakerId == null) SpeakerLink.NONE else SpeakerLink.AUTO,
        totalSpeechMs = 3_000L,
        sampleStartMs = startMs,
        sampleEndMs = startMs + 3_000L,
        centroid = floatArrayOf(1f, 0f, 0f),
    )

    /** Counts embedding documents straight from the collection, so rows detached from a speaker are counted too. */
    private fun storedEmbeddingCount(): Int {
        val collection = requireNotNull(database).let { requireNotNull(it.getCollection("speakers")) }
        return QueryBuilder
            .select(SelectResult.expression(Meta.id))
            .from(DataSource.collection(collection))
            .where(Expression.property("type").equalTo(Expression.string("embedding")))
            .execute()
            .allResults()
            .size
    }

    @Test
    fun createSpeakerTrimsTheNameAndListsIt() = runTest {
        val service = openService()

        val created = service.createSpeaker("  Ana  ").getOrNull()!!

        assertEquals("Ana", created.name)
        assertEquals(listOf(created), service.findSpeakers().getOrNull())
        assertEquals(created, service.findSpeaker(created.id).getOrNull())
    }

    @Test
    fun createdSpeakersStoreDistinctColours() = runTest {
        val service = openService()
        val first = service.createSpeaker("Ana").getOrNull()!!
        val second = service.createSpeaker("Ben").getOrNull()!!
        val third = service.createSpeaker("Carl").getOrNull()!!

        val stored = service.findSpeakers().getOrNull()!!.associate { it.id to it.colorIndex }
        assertEquals(mapOf(first.id to 0, second.id to 1, third.id to 2), stored)
    }

    @Test
    fun renamingASpeakerKeepsItsColour() = runTest {
        val service = openService()
        service.createSpeaker("Ana")
        val ben = service.createSpeaker("Ben").getOrNull()!!

        assertTrue(service.renameSpeaker(ben.id, "Bea").isRight())

        val renamed = service.findSpeaker(ben.id).getOrNull()!!
        assertEquals("Bea", renamed.name)
        assertEquals(1, renamed.colorIndex)
    }

    @Test
    fun namesAreUniqueIgnoringCase() = runTest {
        val service = openService()
        service.createSpeaker("Ana")

        assertIs<SpeakerError.NameTaken>(service.createSpeaker(" ana ").leftOrNull())
    }

    @Test
    fun renameAndDeleteKeepTheLibraryConsistent() = runTest {
        val service = openService()
        val ana = service.createSpeaker("Ana").getOrNull()!!
        val ben = service.createSpeaker("Ben").getOrNull()!!
        service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(ana.id)))

        assertIs<SpeakerError.NameTaken>(service.renameSpeaker(ben.id, "ana").leftOrNull())
        assertEquals(1, storedEmbeddingCount())
        assertTrue(service.renameSpeaker(ben.id, "Bea").isRight())
        assertTrue(service.deleteSpeaker(ana.id).isRight())

        val occurrence = service.findOccurrences("memory-1").getOrNull()!!.single()
        assertEquals(null, occurrence.speakerId)
        assertEquals(SpeakerLink.NONE, occurrence.link)
        assertEquals(listOf("Bea"), service.findSpeakers().getOrNull()!!.map { it.name })
        assertEquals(0, storedEmbeddingCount())
    }

    @Test
    fun replaceOccurrencesStoresTheLinkAndTheVector() = runTest {
        val service = openService()
        val ana = service.createSpeaker("Ana").getOrNull()!!

        service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(ana.id)))

        val occurrence = service.findOccurrences("memory-1").getOrNull()!!.single()
        assertEquals(ana.id, occurrence.speakerId)
        assertEquals(SpeakerLink.AUTO, occurrence.link)
        val embedding = service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!.single()
        assertEquals(occurrence.embeddingId, embedding.id)
        assertTrue(floatArrayOf(1f, 0f, 0f).contentEquals(embedding.vector))
        assertEquals(1, service.findSpeaker(ana.id).getOrNull()!!.embeddingCount)
    }

    @Test
    fun replaceOccurrencesRejectsAnUnknownSpeaker() = runTest {
        val service = openService()

        val result = service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft("missing")))

        assertIs<SpeakerError.NotFound>(result.leftOrNull())
        assertTrue(service.findOccurrences("memory-1").getOrNull()!!.isEmpty())
    }

    @Test
    fun deleteForMemoryRemovesItsRowsIncludingEmbeddingsAttachedToASpeaker() = runTest {
        val service = openService()
        val ana = service.createSpeaker("Ana").getOrNull()!!
        service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(ana.id)))
        service.replaceOccurrences("memory-2", SPEAKER_ANALYSIS_VERSION, listOf(draft(null, startMs = 5_000L)))

        assertTrue(service.deleteForMemory("memory-1").isRight())

        assertTrue(service.findOccurrences("memory-1").getOrNull()!!.isEmpty())
        assertEquals(1, service.findOccurrences("memory-2").getOrNull()!!.size)
        assertTrue(service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!.isEmpty())
        assertEquals(0, service.findSpeaker(ana.id).getOrNull()!!.embeddingCount)
    }

    @Test
    fun speakerKeepsOnlyItsNewestEmbeddings() = runTest {
        val service = openService()
        val ana = service.createSpeaker("Ana").getOrNull()!!

        repeat(MAX_EMBEDDINGS_PER_SPEAKER + 1) { index ->
            now = 1_800_000_000_000L + index
            service.replaceOccurrences(
                "memory-$index",
                SPEAKER_ANALYSIS_VERSION,
                listOf(draft(ana.id, startMs = index * 1_000L)),
            )
        }

        val kept = service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!
        assertEquals(MAX_EMBEDDINGS_PER_SPEAKER, kept.size)
        assertEquals(MAX_EMBEDDINGS_PER_SPEAKER, service.findSpeaker(ana.id).getOrNull()!!.embeddingCount)
        assertFalse(kept.any { it.memoryId == "memory-0" })
        assertTrue(kept.any { it.memoryId == "memory-$MAX_EMBEDDINGS_PER_SPEAKER" })
    }

    @Test
    fun featureIsOffByDefaultAndRequiresConsentBeforeTurningOn() = runTest {
        val service = openService(enabled = false)
        assertEquals(false, service.isFeatureEnabled().getOrNull())

        assertIs<SpeakerError.ConsentRequired>(service.setFeatureEnabled(true).leftOrNull())
        assertEquals(false, service.isFeatureEnabled().getOrNull())
        assertTrue(service.acceptConsent().isRight())
        assertEquals(true, service.isFeatureEnabled().getOrNull())

        assertEquals(true, openService(enabled = false).isFeatureEnabled().getOrNull())
        assertEquals(true, openService(enabled = false).hasCurrentConsent().getOrNull())
    }

    @Test
    fun legacyEnabledSettingWithoutConsentCannotAnalyzeOrStoreRows() = runTest {
        val collection = requireNotNull(requireNotNull(database).getCollection("speakers"))
        val legacySettings = MutableDocument("speaker-settings").apply {
            setString("type", "settings")
            setBoolean("enabled", true)
        }
        collection.save(legacySettings)
        val service = openService(enabled = false)
        val ana = service.createSpeaker("Ana").getOrNull()!!

        assertEquals(false, service.isFeatureEnabled().getOrNull())
        assertEquals(false, service.hasCurrentConsent().getOrNull())
        assertIs<SpeakerError.Disabled>(
            service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(ana.id))).leftOrNull(),
        )
        assertTrue(service.findOccurrences("memory-1").getOrNull()!!.isEmpty())
    }

    @Test
    fun replaceIsRefusedWhileTheFeatureIsOff() = runTest {
        val service = openService(enabled = false)
        val ana = service.createSpeaker("Ana").getOrNull()!!

        val result = service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(ana.id)))

        assertIs<SpeakerError.Disabled>(result.leftOrNull())
        assertTrue(service.findOccurrences("memory-1").getOrNull()!!.isEmpty())
    }

    @Test
    fun replaceIsRefusedAfterWithdrawal() = runTest {
        val service = openService()
        val ana = service.createSpeaker("Ana").getOrNull()!!
        assertTrue(service.clearAll().isRight())

        val result = service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(ana.id)))

        assertIs<SpeakerError.Disabled>(result.leftOrNull())
        assertTrue(service.findOccurrences("memory-1").getOrNull()!!.isEmpty())
        assertEquals(false, service.hasCurrentConsent().getOrNull())
    }

    @Test
    fun assignmentAndRejectCommitOccurrenceAndEmbeddingLinksTogether() = runTest {
        val service = openService()
        val source = service.createSpeaker("Source").getOrNull()!!
        val target = service.createSpeaker("Target").getOrNull()!!
        assertTrue(service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(source.id))).isRight())
        val occurrence = service.findOccurrences("memory-1").getOrNull()!!.single()

        assertTrue(service.assignOccurrence(occurrence.id, target.id).isRight())

        val assigned = service.findOccurrences("memory-1").getOrNull()!!.single()
        assertEquals(target.id, assigned.speakerId)
        assertEquals(SpeakerLink.MANUAL, assigned.link)
        assertEquals(target.id, service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!.single().speakerId)
        val memoryCreatedAt = now - 86_400_000L
        now += 10_000L
        val targetSummary = service.findSpeakerSummaries(mapOf("memory-1" to memoryCreatedAt))
            .getOrNull()!![target.id]!!
        assertEquals(
            1,
            targetSummary.linkedMemoryCount,
        )
        assertEquals(memoryCreatedAt, targetSummary.lastHeardAtEpochMillis)

        assertTrue(service.rejectAutomaticMatch(assigned.id).leftOrNull() is SpeakerError.InvalidInput)
        assertTrue(service.assignOccurrence(assigned.id, source.id).isRight())
        assertTrue(service.replaceOccurrences("memory-2", SPEAKER_ANALYSIS_VERSION, listOf(draft(target.id))).isRight())
        val automatic = service.findOccurrences("memory-2").getOrNull()!!.single()
        assertTrue(service.rejectAutomaticMatch(automatic.id).isRight())

        val rejected = service.findOccurrences("memory-2").getOrNull()!!.single()
        assertEquals(null, rejected.speakerId)
        assertTrue(target.id in rejected.rejectedSpeakerIds)
        assertFalse(service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!
            .any { it.sourceOccurrenceId == automatic.id && it.speakerId == target.id })
    }

    @Test
    fun failedMutationRollsBackPartialWrites() = runTest {
        val service = openService()
        val collection = requireNotNull(requireNotNull(database).getCollection("speakers"))
        val documentId = "partially-written"

        val result = service.applyMutation {
            collection.save(MutableDocument(documentId).apply { setString("type", "speaker") })
            error("injected write failure")
        }

        assertIs<SpeakerError.Persistence>(result.leftOrNull())
        assertNull(collection.getDocument(documentId))
    }

    @Test
    fun deletedSpeakerOccurrenceCanBeManuallyAssignedWithoutRestoringItsVoiceprint() = runTest {
        val service = openService()
        val source = service.createSpeaker("Source").getOrNull()!!
        val target = service.createSpeaker("Target").getOrNull()!!
        service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(source.id)))
        val occurrence = service.findOccurrences("memory-1").getOrNull()!!.single()
        assertTrue(service.deleteSpeaker(source.id).isRight())

        assertTrue(service.assignOccurrence(occurrence.id, target.id).isRight())

        val assigned = service.findOccurrences("memory-1").getOrNull()!!.single()
        assertEquals(target.id, assigned.speakerId)
        assertEquals(SpeakerLink.MANUAL, assigned.link)
        assertEquals(0, service.findSpeaker(target.id).getOrNull()!!.embeddingCount)
        assertTrue(service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!.isEmpty())
    }

    @Test
    fun speakerNameLimitIsEnforcedByThePersistentService() = runTest {
        val service = openService()
        val accepted = "a".repeat(org.deer.borrowai.data.domain.MAX_SPEAKER_NAME_LENGTH)
        val speaker = service.createSpeaker(accepted).getOrNull()!!

        assertEquals(accepted, speaker.name)
        assertIs<SpeakerError.InvalidInput>(
            service.renameSpeaker(speaker.id, "$accepted!").leftOrNull(),
        )
    }

    @Test
    fun mergePreservesTargetAndMovesSpeakerOccurrenceLinks() = runTest {
        val service = openService()
        val source = service.createSpeaker("Source").getOrNull()!!
        val target = service.createSpeaker("Target").getOrNull()!!
        service.replaceOccurrences("memory-source", SPEAKER_ANALYSIS_VERSION, listOf(draft(source.id)))
        service.replaceOccurrences("memory-target", SPEAKER_ANALYSIS_VERSION, listOf(draft(target.id)))

        assertTrue(service.mergeSpeakers(source.id, target.id).isRight())

        assertIs<SpeakerError.NotFound>(service.findSpeaker(source.id).leftOrNull())
        assertEquals(target.name, service.findSpeaker(target.id).getOrNull()!!.name)
        assertEquals(target.colorIndex, service.findSpeaker(target.id).getOrNull()!!.colorIndex)
        assertEquals(target.id, service.findOccurrences("memory-source").getOrNull()!!.single().speakerId)
        assertEquals(2, service.findSpeaker(target.id).getOrNull()!!.embeddingCount)
        assertEquals(
            2,
            service.findSpeakerSummaries(mapOf("memory-source" to 1L, "memory-target" to 2L))
                .getOrNull()!![target.id]?.linkedMemoryCount,
        )
    }

    @Test
    fun clearAllRemovesSpeakerDataAndTurnsTheFeatureOff() = runTest {
        val service = openService()
        service.setFeatureEnabled(true)
        val ana = service.createSpeaker("Ana").getOrNull()!!
        service.replaceOccurrences("memory-1", SPEAKER_ANALYSIS_VERSION, listOf(draft(ana.id)))

        assertTrue(service.clearAll().isRight())

        assertTrue(service.findSpeakers().getOrNull()!!.isEmpty())
        assertTrue(service.findOccurrences("memory-1").getOrNull()!!.isEmpty())
        assertTrue(service.findSpeakerEmbeddings(SPEAKER_ANALYSIS_VERSION).getOrNull()!!.isEmpty())
        assertEquals(false, service.isFeatureEnabled().getOrNull())
        assertEquals(false, service.hasCurrentConsent().getOrNull())
    }
}
