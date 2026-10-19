package org.deer.borrowai.data.service.recording

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder

/**
 * Hands WorkManager workers the runner of the live graph. Workers can start in a process with no
 * graph yet, so they retry until [AppServicesViewModel] publishes one, and it clears the holder
 * before closing the graph.
 */
internal object TranscriptionRunnerHolder {
    @Volatile
    var runner: TranscriptionWorkRunner? = null
}

internal class WorkManagerTranscriptionScheduler(context: Context) : TranscriptionScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueueTranscription() {
        // APPEND chains a request made during an active run, so it is not dropped. REPLACE would cancel the run mid-transcription.
        workManager.enqueueUniqueWork(
            DRAIN_WORK_NAME,
            ExistingWorkPolicy.APPEND,
            OneTimeWorkRequestBuilder<TranscriptionDrainWorker>().build(),
        )
    }

    override fun enqueueSweep() {
        workManager.enqueueUniqueWork(
            SWEEP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TranscriptionSweepWorker>().build(),
        )
    }

    private companion object {
        const val DRAIN_WORK_NAME = "transcription-drain"
        const val SWEEP_WORK_NAME = "transcription-sweep"
    }
}

/** Job 2: transcribes PENDING memories until none remain. */
internal class TranscriptionDrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runner = TranscriptionRunnerHolder.runner ?: return Result.retry()
        return if (runner.drain()) Result.success() else Result.retry()
    }
}

/** Job 1: at startup, requeues memories whose transcription never finished. */
internal class TranscriptionSweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runner = TranscriptionRunnerHolder.runner ?: return Result.retry()
        runner.sweep()
        return Result.success()
    }
}
