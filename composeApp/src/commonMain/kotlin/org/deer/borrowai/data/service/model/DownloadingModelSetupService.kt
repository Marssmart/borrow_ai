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
        result.fold({ error -> fail(error.toFailureReason(), error.describe()) }, { })
        return result
    }

    private suspend fun setupModels(): Either<ModelDownloadError, Unit> = either {
        store.removeObsoleteFiles(ModelCatalog.OBSOLETE_FILE_NAMES)
        val chosen = modelsForRam(totalRamBytes)
        val allSpeechPresent = (chosen.live + chosen.voice).all { store.isPresent(it) }
        if (allSpeechPresent && store.isPresent(chosen.llm)) {
            publishFastReady()
            return@either
        }

        publish(ModelSetupPhase.CHOOSING_MODEL, 0f)
        delay(choosingHoldMillis)
        publish(
            ModelSetupPhase.DOWNLOADING_LIVE_VOICE,
            ModelSetupMilestones.CHOOSING_END,
        )
        downloadFiles(
            chosen.live,
            ModelSetupPhase.DOWNLOADING_LIVE_VOICE,
            ModelSetupMilestones.CHOOSING_END,
            ModelSetupMilestones.LIVE_DOWNLOAD_END,
        ).bind()

        publish(
            ModelSetupPhase.DOWNLOADING_VOICE,
            ModelSetupMilestones.LIVE_DOWNLOAD_END,
        )
        // Files are checked per step, so files shared with the live model are not downloaded twice.
        val voiceDownloaded = downloadFiles(
            chosen.voice,
            ModelSetupPhase.DOWNLOADING_VOICE,
            ModelSetupMilestones.LIVE_DOWNLOAD_END,
            ModelSetupMilestones.VOICE_DOWNLOAD_END,
        ).bind()

        publish(ModelSetupPhase.LOADING_VOICE, ModelSetupMilestones.VOICE_DOWNLOAD_END)
        if (voiceDownloaded) delay(loadingHoldMillis)

        publish(ModelSetupPhase.DOWNLOADING_LLM, ModelSetupMilestones.VOICE_LOAD_END)
        downloadModel(
            chosen.llm,
            ModelSetupPhase.DOWNLOADING_LLM,
            ModelSetupMilestones.VOICE_LOAD_END,
            ModelSetupMilestones.LLM_DOWNLOAD_END,
            alreadyPresent = store.isPresent(chosen.llm),
        ).bind()

        publish(ModelSetupPhase.READY, ModelSetupMilestones.LLM_DOWNLOAD_END)
    }

    /**
     * Downloads each file of a model step in turn. Progress across [startProgress]..[endProgress] is split by file
     * size. Returns whether any file was downloaded, as opposed to already present.
     */
    private suspend fun downloadFiles(
        specs: List<ModelSpec>,
        phase: ModelSetupPhase,
        startProgress: Float,
        endProgress: Float,
    ): Either<ModelDownloadError, Boolean> = either {
        val totalBytes = specs.sumOf { it.sizeBytes }
        var offsetBytes = 0L
        var downloadedAny = false
        for (spec in specs) {
            val bundleOffset = offsetBytes
            val fileStart = startProgress + (endProgress - startProgress) * (offsetBytes.toFloat() / totalBytes)
            offsetBytes += spec.sizeBytes
            val fileEnd = startProgress + (endProgress - startProgress) * (offsetBytes.toFloat() / totalBytes)
            val downloaded = downloadModel(
                spec,
                phase,
                fileStart,
                fileEnd,
                alreadyPresent = store.isPresent(spec),
                bundleOffsetBytes = bundleOffset,
                bundleTotalBytes = totalBytes,
            ).bind()
            downloadedAny = downloadedAny || downloaded
        }
        downloadedAny
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

    private fun ModelDownloadError.describe(): String = when (this) {
        is ModelDownloadError.Network -> "Network: $message${cause?.let { " ($it)" }.orEmpty()}"
        is ModelDownloadError.Storage -> "Storage: $cause"
        is ModelDownloadError.Model -> "Model: $message"
    }

    private fun fail(reason: ModelSetupFailureReason, detail: String) {
        logModelSetupFailure(detail)
        mutableState.update {
            it.copy(phase = ModelSetupPhase.FAILED, failureReason = reason, failureDetail = detail)
        }
    }

    private fun publish(phase: ModelSetupPhase, progress: Float) = transition(phase, progress)

    private fun publishFastReady() {
        FAST_READY_STEPS.forEach { (phase, progress) -> publish(phase, progress) }
        publish(ModelSetupPhase.READY, 1f)
    }

    /**
     * Downloads one file. Byte counts are reported against the whole step bundle: [bundleOffsetBytes] is where this
     * file starts within the bundle and [bundleTotalBytes] is the bundle size, so the live ring stays monotonic.
     */
    private suspend fun downloadModel(
        spec: ModelSpec,
        phase: ModelSetupPhase,
        startProgress: Float,
        endProgress: Float,
        alreadyPresent: Boolean,
        bundleOffsetBytes: Long = 0L,
        bundleTotalBytes: Long = spec.sizeBytes,
    ): Either<ModelDownloadError, Boolean> = either {
        val completedBytes = bundleOffsetBytes + spec.sizeBytes
        if (alreadyPresent) {
            transition(phase, progress = endProgress, bytesDownloaded = completedBytes, bytesTotal = bundleTotalBytes)
            return@either false
        }
        store.preparePartial(spec)
        if (store.commitCompletePartial(spec).bind()) {
            transition(phase, progress = endProgress, bytesDownloaded = completedBytes, bytesTotal = bundleTotalBytes)
            return@either false
        }
        awaitTransferAllowed(startProgress)
        transition(
            phase,
            progress = startProgress,
            bytesDownloaded = bundleOffsetBytes,
            bytesTotal = bundleTotalBytes,
        )
        val downloadedSha256 = downloader.download(
            spec.downloadUrl,
            store.partialPath(spec),
            spec.sizeBytes,
        ) { bytes ->
            val fraction = (bytes.toFloat() / spec.sizeBytes).coerceIn(0f, 1f)
            transition(
                phase = phase,
                progress = startProgress + (endProgress - startProgress) * fraction,
                bytesDownloaded = bundleOffsetBytes + bytes,
                bytesTotal = bundleTotalBytes,
            )
        }.bind()
        store.commit(spec, downloadedSha256).bind()
        transition(phase, progress = endProgress, bytesDownloaded = completedBytes, bytesTotal = bundleTotalBytes)
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
            ModelSetupPhase.DOWNLOADING_LIVE_VOICE to ModelSetupMilestones.CHOOSING_END,
            ModelSetupPhase.DOWNLOADING_VOICE to ModelSetupMilestones.LIVE_DOWNLOAD_END,
            ModelSetupPhase.LOADING_VOICE to ModelSetupMilestones.VOICE_DOWNLOAD_END,
            ModelSetupPhase.DOWNLOADING_LLM to ModelSetupMilestones.VOICE_LOAD_END,
        )
    }
}
