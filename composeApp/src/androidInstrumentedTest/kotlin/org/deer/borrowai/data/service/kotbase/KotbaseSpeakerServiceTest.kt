package org.deer.borrowai.data.service.kotbase

import kotbase.DataSource
import kotbase.Database
import kotbase.Expression
import kotbase.Meta
import kotbase.QueryBuilder
import kotbase.SelectResult
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    /** Opens the store with analysis on, unless [enabled] is false, which keeps the default off setting. */
    private suspend fun openService(enabled: Boolean = true): KotbaseSpeakerService {
        val service = KotbaseSpeakerService.open(
            database = requireNotNull(database),
            epochClock = EpochClock { now },
        ).getOrNull()!!
        if (enabled) service.setFeatureEnabled(true)
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
        val collection = requireNotNull(requireNotNull(database).getCollection("speakers"))
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
    fun featureIsOffByDefaultAndPersistsWhenTurnedOn() = runTest {
        val service = openService(enabled = false)
        assertEquals(false, service.isFeatureEnabled().getOrNull())

        service.setFeatureEnabled(true)

        assertEquals(true, openService(enabled = false).isFeatureEnabled().getOrNull())
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
    }
}
