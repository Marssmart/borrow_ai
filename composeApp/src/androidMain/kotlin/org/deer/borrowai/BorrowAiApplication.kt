package org.deer.borrowai

import android.app.Application
import androidx.work.Configuration
import org.deer.borrowai.data.service.recording.TranscriptionWorkerFactory

class BorrowAiApplication : Application(), Configuration.Provider {
    internal val transcriptionWorkerFactory = TranscriptionWorkerFactory()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(transcriptionWorkerFactory)
            .build()
}
