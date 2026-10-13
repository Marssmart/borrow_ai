package org.deer.borrowai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.deer.borrowai.data.service.ModelSetupPhase
import org.deer.borrowai.data.service.ModelSetupState
import org.deer.borrowai.data.service.model.DownloadingModelSetupService
import org.deer.borrowai.data.service.model.KtorModelDownloader
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.createPlatformModelHttpClient

object ModelDownloadWork {
    const val UNIQUE_WORK_NAME = "borrowai-model-download"
    const val NOTIFICATION_CHANNEL_ID = "model-download"
    const val NOTIFICATION_ID = 1051

    const val KEY_PHASE = "phase"
    const val KEY_PROGRESS = "progress"
    const val KEY_VOICE_NAME = "voiceModelName"
    const val KEY_LLM_NAME = "llmModelName"
    const val KEY_FAILED_DURING = "failedDuring"
}

/**
 * Runs the tier model download as WorkManager work. It is promoted to a foreground service of type dataSync so the
 * download keeps running when the screen is off, and it reports progress through [WorkInfo].
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        ensureNotificationChannel(applicationContext)
        val device = AndroidDeviceEnvironment(applicationContext)
        val client = createPlatformModelHttpClient()
        var lastNotified: Pair<ModelSetupPhase, Int>? = null
        try {
            return coroutineScope {
                val setup = DownloadingModelSetupService(
                    totalRamBytes = device.totalMemoryBytes,
                    store = ModelFileStore(device.modelDirectory),
                    downloader = KtorModelDownloader(client),
                    scope = this,
                )
                val publisher = launch {
                    setup.state.collect { state ->
                        setProgress(state.toWorkData())
                        val notified = state.phase to (state.progress * 100).toInt()
                        if (notified != lastNotified && state.phase != ModelSetupPhase.READY) {
                            lastNotified = notified
                            setForeground(foregroundInfo(applicationContext, state))
                        }
                    }
                }
                setForeground(foregroundInfo(applicationContext, setup.state.value))
                setup.prepare()
                publisher.cancel()
                val finished = setup.state.value
                if (finished.phase == ModelSetupPhase.READY) {
                    Result.success(finished.toWorkData())
                } else {
                    Result.failure(finished.toWorkData())
                }
            }
        } finally {
            client.close()
        }
    }
}

fun ModelSetupState.toWorkData(): Data = workDataOf(
    ModelDownloadWork.KEY_PHASE to phase.name,
    ModelDownloadWork.KEY_PROGRESS to progress,
    ModelDownloadWork.KEY_VOICE_NAME to voiceModelName.orEmpty(),
    ModelDownloadWork.KEY_LLM_NAME to llmModelName.orEmpty(),
    ModelDownloadWork.KEY_FAILED_DURING to failedDuring?.name.orEmpty(),
)

/** Maps a unique-work record to the loader state. Finished work reports its output; running work reports progress. */
fun WorkInfo.toModelSetupState(): ModelSetupState {
    val data = if (state.isFinished) outputData else progress
    return when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelSetupState()
        WorkInfo.State.RUNNING -> data.toModelSetupState(defaultPhase = ModelSetupPhase.NOT_STARTED)
        WorkInfo.State.SUCCEEDED -> data.toModelSetupState(defaultPhase = ModelSetupPhase.READY)
        WorkInfo.State.FAILED -> data.toModelSetupState(defaultPhase = ModelSetupPhase.FAILED)
        WorkInfo.State.CANCELLED -> ModelSetupState(phase = ModelSetupPhase.FAILED)
    }
}

private fun Data.toModelSetupState(defaultPhase: ModelSetupPhase): ModelSetupState {
    val phase = getString(ModelDownloadWork.KEY_PHASE)
        ?.let { name -> ModelSetupPhase.entries.firstOrNull { it.name == name } }
        ?: defaultPhase
    return ModelSetupState(
        phase = phase,
        progress = getFloat(ModelDownloadWork.KEY_PROGRESS, if (phase == ModelSetupPhase.READY) 1f else 0f),
        voiceModelName = getString(ModelDownloadWork.KEY_VOICE_NAME)?.takeIf { it.isNotEmpty() },
        llmModelName = getString(ModelDownloadWork.KEY_LLM_NAME)?.takeIf { it.isNotEmpty() },
        failedDuring = getString(ModelDownloadWork.KEY_FAILED_DURING)
            ?.let { name -> ModelSetupPhase.entries.firstOrNull { it.name == name } },
    )
}

private fun foregroundInfo(context: Context, state: ModelSetupState): ForegroundInfo {
    val notification = notification(context, state)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            ModelDownloadWork.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    } else {
        ForegroundInfo(ModelDownloadWork.NOTIFICATION_ID, notification)
    }
}

private fun notification(context: Context, state: ModelSetupState): Notification {
    val percent = (state.progress * 100).toInt().coerceIn(0, 100)
    val indeterminate = state.phase == ModelSetupPhase.CHOOSING_MODEL || state.phase == ModelSetupPhase.NOT_STARTED
    return NotificationCompat.Builder(context, ModelDownloadWork.NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(context.getString(R.string.notification_model_download_title))
        .setContentText(context.getString(phaseLabelRes(state.phase)))
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, percent, indeterminate)
        .build()
}

private fun phaseLabelRes(phase: ModelSetupPhase): Int = when (phase) {
    ModelSetupPhase.NOT_STARTED,
    ModelSetupPhase.CHOOSING_MODEL -> R.string.notification_model_choosing
    ModelSetupPhase.DOWNLOADING_VOICE -> R.string.notification_model_downloading_voice
    ModelSetupPhase.LOADING_VOICE -> R.string.notification_model_checking_voice
    ModelSetupPhase.DOWNLOADING_LLM -> R.string.notification_model_downloading_llm
    ModelSetupPhase.READY -> R.string.notification_model_downloading_llm
    ModelSetupPhase.FAILED -> R.string.notification_model_failed
}

private fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(ModelDownloadWork.NOTIFICATION_CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            ModelDownloadWork.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_channel_model_download),
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
}
