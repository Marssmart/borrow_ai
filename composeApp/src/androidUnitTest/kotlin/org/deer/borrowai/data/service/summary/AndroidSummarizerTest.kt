package org.deer.borrowai.data.service.summary

import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.deer.borrowai.data.service.llm.LocalLlmRuntime
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.ModelRole
import org.deer.borrowai.data.service.model.ModelSpec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidSummarizerTest {
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
        modelDirectory = Files.createTempDirectory("summarizer-test")
    }

    @AfterTest
    fun deleteDirectory() {
        modelDirectory.toFile().deleteRecursively()
    }

    @Test
    fun missingModelReturnsModelMissingWithoutLoadingEngine() {
        runBlocking {
            val result = summarizer().summarize("Some transcription text")

            assertIs<SummaryResult.ModelMissing>(result)
        }
    }

    @Test
    fun missingModelStaysMissingOnRepeatedCalls() {
        runBlocking {
            val summarizer = summarizer()

            repeat(3) {
                assertEquals(SummaryResult.ModelMissing, summarizer.summarize("Some transcription text"))
            }
        }
    }

    private fun summarizer() = AndroidSummarizer(
        runtime = LocalLlmRuntime(
            store = ModelFileStore(modelDirectory.toString().toPath(), FileSystem.SYSTEM),
            spec = spec,
            cacheDirectory = modelDirectory.toString(),
        ),
    )
}
