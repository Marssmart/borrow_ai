package org.deer.borrowai.data.service.summary

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.ModelSpec

/**
 * Creates a [MemorySummarizer] backed by the tier's LiteRT-LM model for [totalRamBytes]. The model file is checked on
 * each summary, so a model downloaded after startup is picked up without a restart.
 */
internal fun createSummarizer(modelDirectory: Path, totalRamBytes: Long, cacheDirectory: String): MemorySummarizer =
    AndroidSummarizer(
        store = ModelFileStore(modelDirectory),
        spec = ModelCatalog.modelsForRam(totalRamBytes).llm,
        cacheDirectory = cacheDirectory,
    )

/**
 * Summarizes a transcription with a LiteRT-LM model. Returns [SummaryResult.Unavailable] while the model file is
 * missing. The engine loads on the first summary. Summaries run one at a time, and each memory gets a fresh
 * conversation so earlier summaries do not leak into later ones.
 */
internal class AndroidSummarizer(
    private val store: ModelFileStore,
    private val spec: ModelSpec,
    private val cacheDirectory: String,
    private val timeoutMillis: Long = SUMMARY_TIMEOUT_MILLIS,
) : MemorySummarizer {
    // Also guards engine use and close, so close waits for any running inference.
    private val gate = Mutex()
    private var engine: Engine? = null

    override suspend fun summarize(transcription: String): SummaryResult = gate.withLock {
        val present = withContext(Dispatchers.IO) { store.isPresent(spec) }
        if (!present) return@withLock SummaryResult.Unavailable
        try {
            withTimeout(timeoutMillis) {
                withContext(Dispatchers.Default) {
                    val loaded = engine ?: loadEngine().also { engine = it }
                    val conversation = loaded.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(SummaryPrompt.SYSTEM_INSTRUCTION),
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.3, seed = 0),
                        ),
                    )
                    try {
                        val reply = conversation.sendMessage(Message.user(SummaryPrompt.userMessage(transcription)))
                        SummaryPrompt.parse(reply.text())
                    } finally {
                        runCatching { conversation.close() }
                    }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            SummaryResult.Failed(SummarizationTimeoutException(timeoutMillis))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            SummaryResult.Failed(failure)
        }
    }

    override suspend fun close() = gate.withLock {
        runCatching { engine?.close() }
        engine = null
    }

    private fun loadEngine(): Engine {
        val created = Engine(
            EngineConfig(
                modelPath = store.finalPath(spec).toString(),
                backend = Backend.CPU(),
                cacheDir = cacheDirectory,
            ),
        )
        created.initialize()
        return created
    }

    private companion object {
        const val SUMMARY_TIMEOUT_MILLIS = 60_000L
    }
}

private fun Message.text(): String = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
