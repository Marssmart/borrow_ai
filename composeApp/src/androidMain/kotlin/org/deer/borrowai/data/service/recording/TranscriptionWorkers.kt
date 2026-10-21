package org.deer.borrowai.data.service.recording

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Builds the transcription workers with the runner of the live graph. WorkManager is configured
 * with this factory by [org.deer.borrowai.BorrowAiApplication]. The runner is null until
 * [attach] publishes one, so workers started earlier retry, and [detach] clears it before the graph closes.
 */
internal class TranscriptionWorkerFactory : WorkerFactory() {
    @Volatile
    private var runner: TranscriptionWorkRunner? = null

    fun attach(runner: TranscriptionWorkRunner) {
        this.runner = runner
    }

    fun detach() {
        runner = null
    }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        TranscriptionDrainWorker::class.java.name ->
            TranscriptionDrainWorker(appContext, workerParameters) { runner }
        TranscriptionSweepWorker::class.java.name ->
            TranscriptionSweepWorker(appContext, workerParameters) { runner }
        else -> null
    }
}

internal class WorkManagerTranscriptionScheduler(context: Context) : TranscriptionScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueueTranscription() {
        // APPEND_OR_REPLACE chains a request made during an active run so it is not dropped, and replaces a failed or cancelled chain so it cannot block later drains. REPLACE would cancel the run mid-transcription.
        workManager.enqueueUniqueWork(
            DRAIN_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
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
internal class TranscriptionDrainWorker(
    context: Context,
    params: WorkerParameters,
    private val runnerProvider: () -> TranscriptionWorkRunner?,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runner = runnerProvider() ?: return Result.retry()
        return if (runner.drain()) Result.success() else Result.retry()
    }
}

/** Job 1: at startup, requeues memories whose transcription never finished. */
internal class TranscriptionSweepWorker(
    context: Context,
    params: WorkerParameters,
    private val runnerProvider: () -> TranscriptionWorkRunner?,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runner = runnerProvider() ?: return Result.retry()
        return if (runner.sweep()) Result.success() else Result.retry()
    }
}
