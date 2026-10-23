package org.deer.borrowai.data.service.llm

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
 * Creates the shared on-device LLM runtime for the tier's LLM model that matches [totalRamBytes]. The model file is
 * checked on each call, so a model downloaded after startup is picked up without a restart.
 */
internal fun createLocalLlm(modelDirectory: Path, totalRamBytes: Long, cacheDirectory: String): LocalLlmRuntime =
    LocalLlmRuntime(
        store = ModelFileStore(modelDirectory),
        spec = ModelCatalog.modelsForRam(totalRamBytes).llm,
        cacheDirectory = cacheDirectory,
    )

/**
 * Owns one LiteRT-LM [Engine] shared by every on-device LLM feature. The engine loads on the first call. Calls run one
 * at a time, and each call gets a fresh conversation so earlier replies do not leak into later ones. [close] is
 * idempotent.
 */
internal class LocalLlmRuntime(
    private val store: ModelFileStore,
    private val spec: ModelSpec,
    private val cacheDirectory: String,
) {
    // Also guards engine use and close, so close waits for any running inference.
    private val gate = Mutex()
    private var engine: Engine? = null

    suspend fun generate(systemInstruction: String, userMessage: String, timeoutMillis: Long): LlmOutcome = gate.withLock {
        val present = withContext(Dispatchers.IO) { store.isPresent(spec) }
        if (!present) return@withLock LlmOutcome.ModelMissing
        try {
            withTimeout(timeoutMillis) {
                withContext(Dispatchers.Default) {
                    val loaded = engine ?: loadEngine().also { engine = it }
                    val conversation = loaded.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(systemInstruction),
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.3, seed = 0),
                        ),
                    )
                    try {
                        LlmOutcome.Text(conversation.sendMessage(Message.user(userMessage)).text())
                    } finally {
                        runCatching { conversation.close() }
                    }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            LlmOutcome.TimedOut
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            LlmOutcome.Failed(failure)
        }
    }

    suspend fun close() = gate.withLock {
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
}

/** The result of one [LocalLlmRuntime.generate] call. */
internal sealed interface LlmOutcome {
    data class Text(val text: String) : LlmOutcome

    /** The model file is not on the device. Nothing was run. */
    data object ModelMissing : LlmOutcome

    /** The call ran past its time limit. */
    data object TimedOut : LlmOutcome

    data class Failed(val cause: Throwable) : LlmOutcome
}

private fun Message.text(): String = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
