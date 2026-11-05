package org.deer.borrowai.data.service.model

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.deer.borrowai.data.service.background.BackgroundActivity
import org.deer.borrowai.data.service.background.BackgroundActivityTracker

enum class SpeakerModelSetupPhase {
    NOT_STARTED,
    UNSUPPORTED_DEVICE,
    WAITING_FOR_UNMETERED_NETWORK,
    DOWNLOADING,
    READY,
    FAILED,
}

data class SpeakerModelSetupState(
    val phase: SpeakerModelSetupPhase = SpeakerModelSetupPhase.NOT_STARTED,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val bytesTotal: Long = 0L,
    val failure: String? = null,
) {
    val isReady: Boolean
        get() = phase == SpeakerModelSetupPhase.READY
}

interface SpeakerModelSetupService {
    val state: StateFlow<SpeakerModelSetupState>

    suspend fun prepare(): Either<ModelDownloadError, Unit>

    suspend fun downloadOverMobileData()
}

object UnavailableSpeakerModelSetupService : SpeakerModelSetupService {
    override val state: StateFlow<SpeakerModelSetupState> =
        MutableStateFlow(SpeakerModelSetupState(phase = SpeakerModelSetupPhase.UNSUPPORTED_DEVICE))

    override suspend fun prepare(): Either<ModelDownloadError, Unit> =
        ModelDownloadError.Model("Speaker model setup is unavailable.").left()

    override suspend fun downloadOverMobileData() = Unit
}

class DownloadingSpeakerModelSetupService(
    private val store: ModelFileStore,
    private val downloader: ModelDownloader,
    private val network: ModelNetworkPolicy,
    private val scope: CoroutineScope,
    private val activityTracker: BackgroundActivityTracker = BackgroundActivityTracker(),
    private val models: List<ModelSpec> = ModelCatalog.speakerModels,
) : SpeakerModelSetupService {
    private val mutex = Mutex()
    private var attempt: Deferred<Either<ModelDownloadError, Unit>>? = null
    private val mobileDataConsent = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(SpeakerModelSetupState())

    override val state: StateFlow<SpeakerModelSetupState> = mutableState.asStateFlow()

    override suspend fun downloadOverMobileData() {
        if (state.value.phase == SpeakerModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK) {
            mobileDataConsent.value = true
        }
    }

    override suspend fun prepare(): Either<ModelDownloadError, Unit> = activityTracker.track(
        BackgroundActivity.MODEL_DOWNLOAD,
    ) {
        val work = mutex.withLock {
            attempt?.takeIf { it.isActive } ?: scope.async { downloadModels() }.also { attempt = it }
        }
        work.await()
    }

    private suspend fun downloadModels(): Either<ModelDownloadError, Unit> {
        mobileDataConsent.value = false
        val specs = models
        val totalBytes = specs.sumOf { it.sizeBytes }
        return try {
            store.prepareDirectory()
            var completedBytes = 0L
            for (spec in specs) {
                if (store.isPresent(spec)) {
                    completedBytes += spec.sizeBytes
                    publish(SpeakerModelSetupPhase.DOWNLOADING, completedBytes, totalBytes)
                    continue
                }
                store.preparePartial(spec)
                val completePartial = store.commitCompletePartial(spec).fold(
                    ifLeft = { error ->
                        publish(SpeakerModelSetupPhase.FAILED, completedBytes, totalBytes, error.describe())
                        return error.left()
                    },
                    ifRight = { it },
                )
                if (completePartial) {
                    completedBytes += spec.sizeBytes
                    publish(SpeakerModelSetupPhase.DOWNLOADING, completedBytes, totalBytes)
                    continue
                }
                awaitTransferAllowed(completedBytes, totalBytes)
                publish(SpeakerModelSetupPhase.DOWNLOADING, completedBytes, totalBytes)
                val digest = downloader.download(
                    url = spec.downloadUrl,
                    destination = store.partialPath(spec),
                    expectedSizeBytes = spec.sizeBytes,
                ) { bytes ->
                    publish(
                        SpeakerModelSetupPhase.DOWNLOADING,
                        completedBytes + bytes,
                        totalBytes,
                    )
                }.fold(
                    ifLeft = { error ->
                        publish(SpeakerModelSetupPhase.FAILED, completedBytes, totalBytes, error.describe())
                        return error.left()
                    },
                    ifRight = { it },
                )
                store.commit(spec, digest).fold(
                    ifLeft = { error ->
                        publish(SpeakerModelSetupPhase.FAILED, completedBytes, totalBytes, error.describe())
                        return error.left()
                    },
                    ifRight = {},
                )
                completedBytes += spec.sizeBytes
            }
            publish(SpeakerModelSetupPhase.READY, totalBytes, totalBytes)
            Unit.right()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ModelStorageException) {
            fail(ModelDownloadError.Storage(error))
        } catch (error: okio.IOException) {
            fail(ModelDownloadError.Storage(error))
        } catch (error: Exception) {
            fail(ModelDownloadError.Model(error.message.orEmpty()))
        }
    }

    private suspend fun awaitTransferAllowed(completedBytes: Long, totalBytes: Long) {
        val allowed = combine(network.unmetered, mobileDataConsent) { unmetered, consent ->
            unmetered || consent
        }
        if (!allowed.first()) {
            publish(SpeakerModelSetupPhase.WAITING_FOR_UNMETERED_NETWORK, completedBytes, totalBytes)
            allowed.first { it }
        }
    }

    private fun fail(error: ModelDownloadError): Either<ModelDownloadError, Unit> {
        val detail = error.describe()
        publish(SpeakerModelSetupPhase.FAILED, state.value.bytesDownloaded, state.value.bytesTotal, detail)
        return error.left()
    }

    private fun publish(
        phase: SpeakerModelSetupPhase,
        bytesDownloaded: Long,
        bytesTotal: Long,
        failure: String? = null,
    ) {
        mutableState.value = SpeakerModelSetupState(
            phase = phase,
            progress = if (bytesTotal == 0L) 1f else (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f),
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            failure = failure,
        )
    }

    private fun ModelDownloadError.describe(): String = when (this) {
        is ModelDownloadError.Network -> "Network: $message"
        is ModelDownloadError.Storage -> "Storage: $cause"
        is ModelDownloadError.Model -> "Model: $message"
    }
}
