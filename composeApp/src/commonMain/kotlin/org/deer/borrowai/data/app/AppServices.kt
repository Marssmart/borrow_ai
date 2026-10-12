package org.deer.borrowai.data.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.demo.DemoMediaSessionService
import org.deer.borrowai.data.service.demo.DemoModelSetupService
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService

data class AppServices(
    val mode: AppMode,
    val modelSetup: ModelSetupService,
    val memories: MemoryService,
    val mediaSession: MediaSessionService,
) {
    companion object {
        fun createFromStartupFlag(
            startupModeFlag: String?,
            epochClock: EpochClock,
        ): AppServices {
            val mode = AppMode.fromStartupFlag(startupModeFlag)
            return when (mode) {
                AppMode.DEMO -> {
                    val memories = InMemoryDemoMemoryService(epochClock = epochClock)
                    AppServices(
                        mode = mode,
                        modelSetup = DemoModelSetupService(),
                        memories = memories,
                        mediaSession = DemoMediaSessionService(
                            memoryService = memories,
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                        ),
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
