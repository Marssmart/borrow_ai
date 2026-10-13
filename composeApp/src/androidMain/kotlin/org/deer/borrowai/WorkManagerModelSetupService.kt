package org.deer.borrowai

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.ModelSetupState
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelFileStore
import java.util.UUID

/**
 * Enqueues the model download as unique work and mirrors its [WorkInfo] into [state]. The UI only observes state;
 * the download itself runs in [ModelDownloadWorker], so leaving the screen or turning it off does not stop it.
 */
class WorkManagerModelSetupService(
    context: Context,
    scope: CoroutineScope,
) : ModelSetupService {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val mutableState = MutableStateFlow(ModelSetupState())
    private val workInfos = workManager.getWorkInfosForUniqueWorkLiveData(ModelDownloadWork.UNIQUE_WORK_NAME)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observer = Observer<List<WorkInfo>> { infos -> publish(infos) }

    @Volatile
    private var trackedWorkId: UUID? = null

    @Volatile
    private var localReadinessConfirmed = false

    override val state: StateFlow<ModelSetupState> = mutableState.asStateFlow()

    init {
        mainHandler.post { workInfos.observeForever(observer) }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            mainHandler.post { workInfos.removeObserver(observer) }
        }
    }

    override suspend fun prepare() {
        val device = AndroidDeviceEnvironment(appContext)
        val store = ModelFileStore(device.modelDirectory)
        val models = ModelCatalog.modelsForRam(device.totalMemoryBytes)
        val modelsPresent = withContext(Dispatchers.IO) {
            store.prepareDirectory()
            store.isPresent(models.voice) && store.isPresent(models.llm)
        }
        if (modelsPresent) {
            localReadinessConfirmed = true
            mutableState.value = ModelSetupState(
                phase = ModelSetupPhase.READY,
                progress = 1f,
                voiceModelName = models.voice.displayName,
                llmModelName = models.llm.displayName,
                animateTransitions = false,
            )
            return
        }
        if (state.value.phase == ModelSetupPhase.FAILED) mutableState.value = ModelSetupState()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val operation = workManager.enqueueUniqueWork(
            ModelDownloadWork.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        val infos = withContext(Dispatchers.IO) {
            operation.result.get()
            workManager.getWorkInfosForUniqueWork(ModelDownloadWork.UNIQUE_WORK_NAME).get()
        }
        val retained = infos.firstOrNull { it.id == request.id }
            ?: infos.firstOrNull { !it.state.isFinished }
            ?: infos.lastOrNull()
            ?: error("WorkManager did not retain model download work.")
        trackedWorkId = retained.id
        publish(infos)
    }

    /**
     * Shows the active work when one exists, so a relaunch re-attaches to a running download. Once it finishes, the
     * same record keeps its terminal state until new work starts.
     */
    private fun publish(infos: List<WorkInfo>) {
        if (localReadinessConfirmed) return
        val tracked = infos.firstOrNull { it.id == trackedWorkId }
        if (tracked != null) {
            mutableState.value = tracked.toModelSetupState()
            return
        }
        val active = infos.firstOrNull { !it.state.isFinished }
        if (active != null) {
            trackedWorkId = active.id
            mutableState.value = active.toModelSetupState()
        }
    }
}
