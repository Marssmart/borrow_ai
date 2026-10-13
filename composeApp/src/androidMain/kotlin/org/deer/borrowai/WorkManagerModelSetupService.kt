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
import java.util.UUID

/**
 * Enqueues the model download as unique work and mirrors its [WorkInfo] into [state]. The UI only observes state;
 * the download itself runs in [ModelDownloadWorker], so leaving the screen or turning it off does not stop it.
 */
class WorkManagerModelSetupService(
    context: Context,
    scope: CoroutineScope,
) : ModelSetupService {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val mutableState = MutableStateFlow(ModelSetupState())
    private val workInfos = workManager.getWorkInfosForUniqueWorkLiveData(ModelDownloadWork.UNIQUE_WORK_NAME)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observer = Observer<List<WorkInfo>> { infos -> publish(infos) }

    @Volatile
    private var trackedWorkId: UUID? = null

    override val state: StateFlow<ModelSetupState> = mutableState.asStateFlow()

    init {
        mainHandler.post { workInfos.observeForever(observer) }
        scope.coroutineContext[Job]?.invokeOnCompletion {
            mainHandler.post { workInfos.removeObserver(observer) }
        }
    }

    override suspend fun prepare() {
        if (state.value.phase == ModelSetupPhase.READY) return
        if (state.value.phase == ModelSetupPhase.FAILED) mutableState.value = ModelSetupState()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val operation = workManager.enqueueUniqueWork(
            ModelDownloadWork.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        withContext(Dispatchers.IO) { operation.result.get() }
        trackedWorkId = request.id
    }

    /**
     * Shows the active work when one exists, so a relaunch re-attaches to a running download. Once it finishes, the
     * same record keeps its terminal state until new work starts.
     */
    private fun publish(infos: List<WorkInfo>) {
        val active = infos.firstOrNull { !it.state.isFinished }
        val tracked = if (active != null) {
            trackedWorkId = active.id
            active
        } else {
            infos.firstOrNull { it.id == trackedWorkId } ?: return
        }
        mutableState.value = tracked.toModelSetupState()
    }
}
