package org.deer.borrowai.data.service.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val scope: CoroutineScope,
    private val choosingHoldMillis: Long = CHOOSING_HOLD_MILLIS,
    private val loadingHoldMillis: Long = LOADING_HOLD_MILLIS,
    private val modelsForRam: (totalRamBytes: Long) -> TierModels = ModelCatalog::modelsForRam,
) : ModelSetupService {
    private val setupMutex = Mutex()
    private val mutableState = MutableStateFlow(ModelSetupState())
    private var activeSetup: Job? = null

    override val state = mutableState.asStateFlow()

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
            store.prepareDirectory()
            val chosen = modelsForRam(totalRamBytes)
            val voicePresent = store.isPresent(chosen.voice)
            val llmPresent = store.isPresent(chosen.llm)
            if (voicePresent && llmPresent) {
                publishFastReady(chosen)
                return
            }

            publish(ModelSetupPhase.CHOOSING_MODEL, 0f, chosen)
            delay(choosingHoldMillis)
            publish(
                ModelSetupPhase.DOWNLOADING_VOICE,
                ModelSetupMilestones.CHOOSING_END,
                chosen,
            )
            val voiceDownloaded = downloadModel(
                chosen.voice,
                ModelSetupPhase.DOWNLOADING_VOICE,
                ModelSetupMilestones.CHOOSING_END,
                ModelSetupMilestones.VOICE_DOWNLOAD_END,
            )

            publish(ModelSetupPhase.LOADING_VOICE, ModelSetupMilestones.VOICE_DOWNLOAD_END, chosen)
            if (voiceDownloaded) delay(loadingHoldMillis)

            publish(ModelSetupPhase.DOWNLOADING_LLM, ModelSetupMilestones.VOICE_LOAD_END, chosen)
            downloadModel(
                chosen.llm,
                ModelSetupPhase.DOWNLOADING_LLM,
                ModelSetupMilestones.VOICE_LOAD_END,
                ModelSetupMilestones.LLM_DOWNLOAD_END,
            )

            publish(ModelSetupPhase.READY, ModelSetupMilestones.LLM_DOWNLOAD_END, chosen)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(
                phase = ModelSetupPhase.FAILED,
                failedDuring = mutableState.value.phase,
                retryableFailure = (error as? ModelDownloadException)?.retryable == true,
            )
        }
    }

    private fun publish(phase: ModelSetupPhase, progress: Float, models: TierModels) {
        mutableState.value = ModelSetupState(
            phase = phase,
            progress = progress,
            voiceModelName = models.voice.displayName,
            llmModelName = models.llm.displayName,
        )
    }

    private fun publishFastReady(models: TierModels) {
        publish(ModelSetupPhase.CHOOSING_MODEL, 0f, models)
        publish(ModelSetupPhase.DOWNLOADING_VOICE, ModelSetupMilestones.CHOOSING_END, models)
        publish(ModelSetupPhase.LOADING_VOICE, ModelSetupMilestones.VOICE_DOWNLOAD_END, models)
        publish(ModelSetupPhase.DOWNLOADING_LLM, ModelSetupMilestones.VOICE_LOAD_END, models)
        mutableState.value = ModelSetupState(
            phase = ModelSetupPhase.READY,
            progress = 1f,
            voiceModelName = models.voice.displayName,
            llmModelName = models.llm.displayName,
            animateTransitions = false,
        )
    }

    private suspend fun downloadModel(
        spec: ModelSpec,
        phase: ModelSetupPhase,
        startProgress: Float,
        endProgress: Float,
    ): Boolean {
        if (store.isPresent(spec)) {
            mutableState.value = mutableState.value.copy(phase = phase, progress = endProgress, bytesDownloaded = 0L, bytesTotal = 0L)
            return false
        }
        val downloadedSha256 = downloader.download(spec.downloadUrl, store.partialPath(spec)) { bytes ->
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

    private companion object {
        const val CHOOSING_HOLD_MILLIS = 1_200L
        const val LOADING_HOLD_MILLIS = 600L
    }
}
