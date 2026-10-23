package org.deer.borrowai.data.service.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.ModelRole
import org.deer.borrowai.data.service.model.ModelSpec
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalLlmRuntimeTest {
    private lateinit var modelDirectory: java.nio.file.Path

    private val spec = ModelSpec(
        displayName = "Test LLM",
        role = ModelRole.LLM,
        fileName = "test-llm.litertlm",
        downloadUrl = "https://example.invalid/test-llm.litertlm",
        revision = "test",
        sizeBytes = 1024L,
        sha256 = "0".repeat(64),
    )

    @BeforeTest
    fun createDirectory() {
        modelDirectory = Files.createTempDirectory("runtime-test")
    }

    @AfterTest
    fun deleteDirectory() {
        modelDirectory.toFile().deleteRecursively()
    }

    @Test
    fun missingModelReturnsModelMissingAndNeverLoadsEngine() {
        val factory = FakeEngineFactory()

        val result = runBlocking { runtime(factory).generate("system", "hello", TIMEOUT_MILLIS) }

        assertEquals(LlmOutcome.ModelMissing, result)
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun presentModelReturnsReplyText() {
        writeModel()

        val result = runBlocking { runtime(FakeEngineFactory()).generate("system", "hello", TIMEOUT_MILLIS) }

        assertEquals(LlmOutcome.Text("reply to hello"), result)
    }

    @Test
    fun engineLoadsOnceAndEachCallGetsAFreshConversation() {
        writeModel()
        val factory = FakeEngineFactory()
        val runtime = runtime(factory)

        runBlocking {
            runtime.generate("system", "first", TIMEOUT_MILLIS)
            runtime.generate("system", "second", TIMEOUT_MILLIS)
        }

        val engine = factory.created.single()
        assertEquals(2, engine.conversations.size)
        assertEquals(listOf(1, 1), engine.conversations.map { it.closeCount })
    }

    @Test
    fun systemInstructionIsPassedToTheConversation() {
        writeModel()
        val factory = FakeEngineFactory()

        runBlocking { runtime(factory).generate("be brief", "hello", TIMEOUT_MILLIS) }

        assertEquals(listOf("be brief"), factory.created.single().systemInstructions)
    }

    @Test
    fun replyFailureIsReportedAndConversationStillCloses() {
        writeModel()
        val factory = FakeEngineFactory(onReply = { throw IllegalStateException("boom") })

        val result = runBlocking { runtime(factory).generate("system", "hello", TIMEOUT_MILLIS) }

        val failed = assertIs<LlmOutcome.Failed>(result)
        assertEquals("boom", failed.cause.message)
        assertEquals(1, factory.created.single().conversations.single().closeCount)
    }

    @Test
    fun loadFailureIsReportedAndNextCallRetriesTheLoad() {
        writeModel()
        val factory = FakeEngineFactory(loadFailure = IllegalStateException("no engine"))
        val runtime = runtime(factory)

        val failed = runBlocking { runtime.generate("system", "hello", TIMEOUT_MILLIS) }
        factory.loadFailure = null
        val recovered = runBlocking { runtime.generate("system", "hello", TIMEOUT_MILLIS) }

        assertEquals("no engine", assertIs<LlmOutcome.Failed>(failed).cause.message)
        assertEquals(LlmOutcome.Text("reply to hello"), recovered)
        assertEquals(1, factory.created.size)
    }

    @Test
    fun replyPastTheTimeLimitReturnsTimedOut() {
        writeModel()
        val factory = FakeEngineFactory(onReply = { Thread.sleep(300); "late" })

        val result = runBlocking { runtime(factory).generate("system", "hello", timeoutMillis = 20) }

        assertEquals(LlmOutcome.TimedOut, result)
    }

    @Test
    fun closeClosesTheEngineOnceAndIsIdempotent() {
        writeModel()
        val factory = FakeEngineFactory()
        val runtime = runtime(factory)

        runBlocking {
            runtime.generate("system", "hello", TIMEOUT_MILLIS)
            runtime.close()
            runtime.close()
        }

        assertEquals(1, factory.created.single().closeCount)
    }

    @Test
    fun closeWaitsForRunningInferenceBeforeClosingTheEngine() {
        writeModel()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val factory = FakeEngineFactory(onReply = {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            "done"
        })
        val runtime = runtime(factory)

        runBlocking {
            val inference = async(Dispatchers.Default) { runtime.generate("system", "hello", 10_000) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val closing = async(Dispatchers.Default) { runtime.close() }
            delay(100)

            assertEquals(0, factory.created.single().closeCount)
            release.countDown()

            assertEquals(LlmOutcome.Text("done"), inference.await())
            closing.await()
        }

        assertEquals(1, factory.created.single().closeCount)
    }

    @Test
    fun engineCloseFailureDoesNotEscapeClose() {
        writeModel()
        val factory = FakeEngineFactory(closeFailure = IllegalStateException("close failed"))
        val runtime = runtime(factory)

        runBlocking {
            runtime.generate("system", "hello", TIMEOUT_MILLIS)
            runtime.close()
        }

        assertEquals(1, factory.created.single().closeCount)
    }

    private fun runtime(factory: LlmEngineFactory) = LocalLlmRuntime(
        store = ModelFileStore(modelDirectory.toString().toPath(), FileSystem.SYSTEM) { spec.sha256 },
        spec = spec,
        cacheDirectory = modelDirectory.toString(),
        engineFactory = factory,
    )

    private fun writeModel() {
        Files.write(modelDirectory.resolve(spec.fileName), ByteArray(spec.sizeBytes.toInt()))
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}

private class FakeEngineFactory(
    private val onReply: (String) -> String = { "reply to $it" },
    var loadFailure: Throwable? = null,
    private val closeFailure: Throwable? = null,
) : LlmEngineFactory {
    val created = mutableListOf<FakeEngine>()

    override fun create(modelPath: String, cacheDirectory: String): LlmEngine {
        loadFailure?.let { throw it }
        return FakeEngine(onReply, closeFailure).also { created += it }
    }
}

private class FakeEngine(
    private val onReply: (String) -> String,
    private val closeFailure: Throwable?,
) : LlmEngine {
    val conversations = mutableListOf<FakeConversation>()
    val systemInstructions = mutableListOf<String>()
    var closeCount = 0
        private set

    override fun startConversation(systemInstruction: String): LlmConversation {
        systemInstructions += systemInstruction
        return FakeConversation(onReply).also { conversations += it }
    }

    override fun close() {
        closeCount++
        closeFailure?.let { throw it }
    }
}

private class FakeConversation(private val onReply: (String) -> String) : LlmConversation {
    var closeCount = 0
        private set

    override fun reply(userMessage: String): String = onReply(userMessage)

    override fun close() {
        closeCount++
    }
}
