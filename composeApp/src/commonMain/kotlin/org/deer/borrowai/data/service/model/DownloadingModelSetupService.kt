package org.deer.borrowai.data.service.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.raise.either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.service.ModelSetupFailureReason
import org.deer.borrowai.data.service.ModelSetupMilestones
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState

/**
 * Chooses the tier models from the device RAM and downloads them to static files.
 * The work runs in [scope] so a recreated screen joins the same download instead of restarting it.
 */
class DownloadingModelSetupService(
    private val totalRamBytes: Long,
    private val store: ModelFileStore,
    private val downloader: ModelDownloader,
    private val network: ModelNetworkPolicy,
    private val scope: CoroutineScope,
    private val choosingHoldMillis: Long = CHOOSING_HOLD_MILLIS,
    private val loadingHoldMillis: Long = LOADING_HOLD_MILLIS,
    private val modelsForRam: (totalRamBytes: Long) -> TierModels = ModelCatalog::modelsForRam,
) : ModelSetupService {
    private val setupMutex = Mutex()
    private var setupAttempt: Deferred<Either<ModelDownloadError, Unit>>? = null

    private val mutableState = MutableStateFlow(ModelSetupState())
    override val state: StateFlow<ModelSetupState> = mutableState.asStateFlow()

    private val mobileDataConsent = MutableStateFlow(false)

    override suspend fun downloadOverMobileData() {
        if (state.value.phase == ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK) {
            mobileDataConsent.value = true
        }
    }

    override suspend fun prepare(): Either<ModelDownloadError, Unit> {
        val attempt = setupMutex.withLock {
            if (state.value.isReady) return Unit.right()
            setupAttempt?.takeIf { it.isActive }
                ?: scope.async { runSetup() }.also { setupAttempt = it }
        }
        return attempt.await()
    }

    private suspend fun runSetup(): Either<ModelDownloadError, Unit> {
        val result = try {
            mobileDataConsent.value = false
            store.prepareDirectory()
            setupModels()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ModelStorageException) {
            ModelDownloadError.Storage(error).left()
        } catch (error: okio.IOException) {
            ModelDownloadError.Storage(error).left()
        } catch (error: Exception) {
            ModelDownloadError.Model(error.message.orEmpty()).left()
        }
        result.fold({ error -> fail(error.toFailureReason()) }, { })
        return result
    }

    private suspend fun setupModels(): Either<ModelDownloadError, Unit> = either {
        val chosen = modelsForRam(totalRamBytes)
        val voicePresent = store.isPresent(chosen.voice)
        val llmPresent = store.isPresent(chosen.llm)
        if (voicePresent && llmPresent) {
            publishFastReady()
            return@either
        }

        publish(ModelSetupPhase.CHOOSING_MODEL, 0f)
        delay(choosingHoldMillis)
        publish(
            ModelSetupPhase.DOWNLOADING_VOICE,
            ModelSetupMilestones.CHOOSING_END,
        )
        val voiceDownloaded = downloadModel(
            chosen.voice,
            ModelSetupPhase.DOWNLOADING_VOICE,
            ModelSetupMilestones.CHOOSING_END,
            ModelSetupMilestones.VOICE_DOWNLOAD_END,
            alreadyPresent = voicePresent,
        ).bind()

        publish(ModelSetupPhase.LOADING_VOICE, ModelSetupMilestones.VOICE_DOWNLOAD_END)
        if (voiceDownloaded) delay(loadingHoldMillis)

        publish(ModelSetupPhase.DOWNLOADING_LLM, ModelSetupMilestones.VOICE_LOAD_END)
        downloadModel(
            chosen.llm,
            ModelSetupPhase.DOWNLOADING_LLM,
            ModelSetupMilestones.VOICE_LOAD_END,
            ModelSetupMilestones.LLM_DOWNLOAD_END,
            alreadyPresent = llmPresent,
        ).bind()

        publish(ModelSetupPhase.READY, ModelSetupMilestones.LLM_DOWNLOAD_END)
    }

    private fun ModelDownloadError.toFailureReason(): ModelSetupFailureReason = when (this) {
        is ModelDownloadError.Network -> ModelSetupFailureReason.NETWORK
        is ModelDownloadError.Storage -> ModelSetupFailureReason.STORAGE
        is ModelDownloadError.Model -> ModelSetupFailureReason.MODEL
    }

    private fun transition(
        phase: ModelSetupPhase,
        progress: Float = 0f,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
    ) {
        mutableState.value = ModelSetupState(
            phase = phase,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
        )
    }

    private fun fail(reason: ModelSetupFailureReason) {
        mutableState.update { it.copy(phase = ModelSetupPhase.FAILED, failureReason = reason) }
    }

    private fun publish(phase: ModelSetupPhase, progress: Float) = transition(phase, progress)

    private fun publishFastReady() {
        FAST_READY_STEPS.forEach { (phase, progress) -> publish(phase, progress) }
        publish(ModelSetupPhase.READY, 1f)
    }

    private suspend fun downloadModel(
        spec: ModelSpec,
        phase: ModelSetupPhase,
        startProgress: Float,
        endProgress: Float,
        alreadyPresent: Boolean,
    ): Either<ModelDownloadError, Boolean> = either {
        if (alreadyPresent) {
            transition(phase, progress = endProgress)
            return@either false
        }
        store.preparePartial(spec)
        if (store.commitCompletePartial(spec).bind()) {
            transition(phase, progress = endProgress)
            return@either false
        }
        awaitTransferAllowed(startProgress)
        transition(phase, progress = startProgress)
        val downloadedSha256 = downloader.download(
            spec.downloadUrl,
            store.partialPath(spec),
            spec.sizeBytes,
        ) { bytes ->
            val fraction = (bytes.toFloat() / spec.sizeBytes).coerceIn(0f, 1f)
            transition(
                phase = phase,
                progress = startProgress + (endProgress - startProgress) * fraction,
                bytesDownloaded = bytes,
                bytesTotal = spec.sizeBytes,
            )
        }.bind()
        store.commit(spec, downloadedSha256).bind()
        transition(phase, progress = endProgress)
        true
    }

    /** Holds a required transfer until the network is unmetered or the user consents to mobile data for this run. */
    private suspend fun awaitTransferAllowed(progress: Float) {
        val allowed = combine(network.unmetered, mobileDataConsent) { unmetered, consent -> unmetered || consent }
        if (!allowed.first()) {
            transition(
                phase = ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK,
                progress = progress,
            )
            allowed.first { it }
        }
    }

    private companion object {
        const val CHOOSING_HOLD_MILLIS = 1_200L
        const val LOADING_HOLD_MILLIS = 600L

        val FAST_READY_STEPS = listOf(
            ModelSetupPhase.CHOOSING_MODEL to 0f,
            ModelSetupPhase.DOWNLOADING_VOICE to ModelSetupMilestones.CHOOSING_END,
            ModelSetupPhase.LOADING_VOICE to ModelSetupMilestones.VOICE_DOWNLOAD_END,
            ModelSetupPhase.DOWNLOADING_LLM to ModelSetupMilestones.VOICE_LOAD_END,
        )
    }
}
