package org.deer.borrowai.data.service.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val engineFactory: LlmEngineFactory = LiteRtLlmEngineFactory(),
    // Native inference cannot be interrupted, so it runs here and may outlive a timed-out call.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // Guards engine use and close. Calls and close are serialized through it.
    private val gate = Mutex()
    private var engine: LlmEngine? = null
    private var inFlight: Deferred<LlmOutcome>? = null

    suspend fun generate(systemInstruction: String, userMessage: String, timeoutMillis: Long): LlmOutcome = gate.withLock {
        val present = withContext(Dispatchers.IO) { store.isPresent(spec) }
        if (!present) return@withLock LlmOutcome.ModelMissing
        withTimeoutOrNull(timeoutMillis) {
            // A previous call that timed out may still be running on the engine. Wait for it before starting another.
            inFlight?.join()
            val work = scope.async(Dispatchers.Default) { infer(systemInstruction, userMessage) }
            inFlight = work
            work.await()
        } ?: LlmOutcome.TimedOut
    }

    suspend fun close() = gate.withLock {
        // Never close the engine under a running reply.
        inFlight?.join()
        runCatching { engine?.close() }
        engine = null
    }

    private fun infer(systemInstruction: String, userMessage: String): LlmOutcome = try {
        val loaded = engine ?: engineFactory.create(store.finalPath(spec).toString(), cacheDirectory)
            .also { engine = it }
        val conversation = loaded.startConversation(systemInstruction)
        try {
            LlmOutcome.Text(conversation.reply(userMessage))
        } finally {
            runCatching { conversation.close() }
        }
    } catch (failure: Throwable) {
        LlmOutcome.Failed(failure)
    }
}

/** Seam over LiteRT-LM so the runtime lifecycle can be tested without a real model. */
internal fun interface LlmEngineFactory {
    fun create(modelPath: String, cacheDirectory: String): LlmEngine
}

internal interface LlmEngine {
    fun startConversation(systemInstruction: String): LlmConversation

    fun close()
}

internal interface LlmConversation {
    fun reply(userMessage: String): String

    fun close()
}

internal class LiteRtLlmEngineFactory : LlmEngineFactory {
    override fun create(modelPath: String, cacheDirectory: String): LlmEngine {
        val created = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = cacheDirectory))
        created.initialize()
        return LiteRtLlmEngine(created)
    }
}

private class LiteRtLlmEngine(private val engine: Engine) : LlmEngine {
    override fun startConversation(systemInstruction: String): LlmConversation = LiteRtLlmConversation(
        engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.3, seed = 0),
            ),
        ),
    )

    override fun close() {
        engine.close()
    }
}

private class LiteRtLlmConversation(private val conversation: Conversation) : LlmConversation {
    override fun reply(userMessage: String): String = conversation.sendMessage(Message.user(userMessage)).text()

    override fun close() {
        conversation.close()
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
