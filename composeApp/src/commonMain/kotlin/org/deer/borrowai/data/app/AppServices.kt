package org.deer.borrowai.data.app

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.MutableCloseOnce
import org.deer.borrowai.data.service.demo.DemoMediaSessionService
import org.deer.borrowai.data.service.demo.DemoModelSetupService
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService

class AppServices private constructor(
    val mode: AppMode,
    val modelSetup: ModelSetupService,
    val memories: MemoryService,
    val mediaSession: MediaSessionService,
    private val graphJob: CompletableJob,
) {
    private val closeOnce = MutableCloseOnce()

    fun close() {
        closeOnce.closeOnce {
            try {
                mediaSession.close()
            } finally {
                graphJob.cancel()
            }
        }
    }

    companion object {
        fun createFromStartupFlag(
            startupModeFlag: String?,
            epochClock: EpochClock,
        ): AppServices = createFromStartupFlag(
            startupModeFlag = startupModeFlag,
            epochClock = epochClock,
            dispatcher = Dispatchers.Default,
        )

        internal fun createFromStartupFlag(
            startupModeFlag: String?,
            epochClock: EpochClock,
            dispatcher: CoroutineDispatcher,
        ): AppServices {
            val mode = AppMode.fromStartupFlag(startupModeFlag)
            return when (mode) {
                AppMode.DEMO -> {
                    val graphJob = SupervisorJob()
                    val graphScope = CoroutineScope(graphJob + dispatcher)
                    val memories = InMemoryDemoMemoryService(epochClock = epochClock)
                    AppServices(
                        mode = mode,
                        modelSetup = DemoModelSetupService(),
                        memories = memories,
                        mediaSession = DemoMediaSessionService(
                            memoryService = memories,
                            scope = graphScope,
                        ),
                        graphJob = graphJob,
                    )
                }

                AppMode.REAL -> throw RealServicesUnavailableException()
            }
        }
    }
}

class RealServicesUnavailableException : IllegalStateException(
    "Real mode was requested, but production data services have not been implemented.",
)
