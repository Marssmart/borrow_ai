package org.deer.borrowai.data.service.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.service.ModelSetupMilestones
import org.deer.borrowai.data.service.ModelSetupFailureReason
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
    private val mutableState = MutableStateFlow(ModelSetupState())
    private var activeSetup: Job? = null

    override val state = mutableState.asStateFlow()

    private val mobileDataConsent = MutableStateFlow(false)

    override suspend fun downloadOverMobileData() {
        if (mutableState.value.phase == ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK) {
            mobileDataConsent.value = true
        }
    }

    override suspend fun prepare() {
        val setup = setupMutex.withLock {
            if (mutableState.value.phase == ModelSetupPhase.READY) return
            activeSetup?.takeIf { it.isActive }
                ?: scope.launch { runSetup() }.also { activeSetup = it }
        }
        setup.join()
    }

    private suspend fun runSetup() {
        try {
            mobileDataConsent.value = false
            store.prepareDirectory()
            val chosen = modelsForRam(totalRamBytes)
            val voicePresent = store.isPresent(chosen.voice)
            val llmPresent = store.isPresent(chosen.llm)
            if (voicePresent && llmPresent) {
                publishFastReady()
                return
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
            )

            publish(ModelSetupPhase.LOADING_VOICE, ModelSetupMilestones.VOICE_DOWNLOAD_END)
            if (voiceDownloaded) delay(loadingHoldMillis)

            publish(ModelSetupPhase.DOWNLOADING_LLM, ModelSetupMilestones.VOICE_LOAD_END)
            downloadModel(
                chosen.llm,
                ModelSetupPhase.DOWNLOADING_LLM,
                ModelSetupMilestones.VOICE_LOAD_END,
                ModelSetupMilestones.LLM_DOWNLOAD_END,
                alreadyPresent = llmPresent,
            )

            publish(ModelSetupPhase.READY, ModelSetupMilestones.LLM_DOWNLOAD_END)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                phase = ModelSetupPhase.FAILED,
                failureReason = when {
                    (error as? ModelDownloadException)?.retryable == true -> ModelSetupFailureReason.NETWORK
                    error is ModelStorageException || error is okio.IOException ->
                        ModelSetupFailureReason.STORAGE
                    else -> ModelSetupFailureReason.MODEL
                },
            )
        }
    }

    private fun publish(phase: ModelSetupPhase, progress: Float) {
        mutableState.value = ModelSetupState(
            phase = phase,
            progress = progress,
        )
    }

    private fun publishFastReady() {
        publish(ModelSetupPhase.CHOOSING_MODEL, 0f)
        publish(ModelSetupPhase.DOWNLOADING_VOICE, ModelSetupMilestones.CHOOSING_END)
        publish(ModelSetupPhase.LOADING_VOICE, ModelSetupMilestones.VOICE_DOWNLOAD_END)
        publish(ModelSetupPhase.DOWNLOADING_LLM, ModelSetupMilestones.VOICE_LOAD_END)
        mutableState.value = ModelSetupState(
            phase = ModelSetupPhase.READY,
            progress = 1f,
        )
    }

    private suspend fun downloadModel(
        spec: ModelSpec,
        phase: ModelSetupPhase,
        startProgress: Float,
        endProgress: Float,
        alreadyPresent: Boolean,
    ): Boolean {
        if (alreadyPresent) {
            mutableState.value = mutableState.value.copy(phase = phase, progress = endProgress, bytesDownloaded = 0L, bytesTotal = 0L)
            return false
        }
        store.preparePartial(spec)
        if (store.commitCompletePartial(spec)) {
            mutableState.value = mutableState.value.copy(phase = phase, progress = endProgress, bytesDownloaded = 0L, bytesTotal = 0L)
            return false
        }
        awaitTransferAllowed(startProgress)
        mutableState.value = mutableState.value.copy(phase = phase, progress = startProgress, bytesDownloaded = 0L, bytesTotal = 0L)
        val downloadedSha256 = downloader.download(
            spec.downloadUrl,
            store.partialPath(spec),
            spec.sizeBytes,
        ) { bytes ->
            val fraction = (bytes.toFloat() / spec.sizeBytes).coerceIn(0f, 1f)
            mutableState.value = mutableState.value.copy(
                phase = phase,
                progress = startProgress + (endProgress - startProgress) * fraction,
                bytesDownloaded = bytes,
                bytesTotal = spec.sizeBytes,
            )
        }
        store.commit(spec, downloadedSha256)
        mutableState.value = mutableState.value.copy(phase = phase, progress = endProgress, bytesDownloaded = 0L, bytesTotal = 0L)
        return true
    }

    /** Holds a required transfer until the network is unmetered or the user consents to mobile data for this run. */
    private suspend fun awaitTransferAllowed(progress: Float) {
        val allowed = combine(network.unmetered, mobileDataConsent) { unmetered, consent -> unmetered || consent }
        if (!allowed.first()) {
            mutableState.value = mutableState.value.copy(
                phase = ModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK,
                progress = progress,
                bytesDownloaded = 0L,
                bytesTotal = 0L,
            )
            allowed.first { it }
        }
    }

    private companion object {
        const val CHOOSING_HOLD_MILLIS = 1_200L
        const val LOADING_HOLD_MILLIS = 600L
    }
}
