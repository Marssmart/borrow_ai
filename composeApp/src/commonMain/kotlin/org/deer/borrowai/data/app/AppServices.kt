package org.deer.borrowai.data.app

import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.deer.borrowai.data.domain.EpochClock
import org.deer.borrowai.data.service.MediaSessionService
import org.deer.borrowai.data.service.MemoryService
import org.deer.borrowai.data.service.ModelSetupService
import org.deer.borrowai.data.service.demo.DemoMediaSessionService
import org.deer.borrowai.data.service.demo.DemoModelSetupService
import org.deer.borrowai.data.service.demo.InMemoryDemoMemoryService
import org.deer.borrowai.data.service.model.DownloadingModelSetupService
import org.deer.borrowai.data.service.model.KtorModelDownloader
import org.deer.borrowai.data.service.model.ModelCatalog
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.createPlatformModelHttpClient

class AppServices private constructor(
    val variant: ServiceVariant,
    val modelSetup: ModelSetupService,
    val memories: MemoryService,
    val mediaSession: MediaSessionService,
    private val graphJob: CompletableJob,
    private val httpClient: HttpClient?,
) {
    private val closed = MutableStateFlow(false)

    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return

        try {
            mediaSession.close()
        } finally {
            try {
                graphJob.cancel()
            } finally {
                httpClient?.close()
            }
        }
    }

    companion object {
        fun createFromServiceVariant(
            serviceVariantId: String?,
            epochClock: EpochClock,
            device: DeviceEnvironment,
        ): AppServices = createFromServiceVariant(
            serviceVariantId = serviceVariantId,
            epochClock = epochClock,
            device = device,
            dispatcher = Dispatchers.Default,
        )

        internal fun createFromServiceVariant(
            serviceVariantId: String?,
            epochClock: EpochClock,
            device: DeviceEnvironment,
            dispatcher: CoroutineDispatcher,
        ): AppServices {
            val variant = ServiceVariants.fromId(serviceVariantId)
            val graphJob = SupervisorJob()
            val graphScope = CoroutineScope(graphJob + dispatcher)
            return try {
                buildGraph(variant, epochClock, device, graphScope, graphJob)
            } catch (error: Throwable) {
                graphJob.cancel()
                throw error
            }
        }

        private fun buildGraph(
            variant: ServiceVariant,
            epochClock: EpochClock,
            device: DeviceEnvironment,
            graphScope: CoroutineScope,
            graphJob: CompletableJob,
        ): AppServices {
            val memories = when (variant.modeOf(DataService.MEMORIES)) {
                ServiceMode.DEMO -> InMemoryDemoMemoryService(epochClock = epochClock)
                ServiceMode.REAL -> throw RealServicesUnavailableException(DataService.MEMORIES)
            }
            val mediaSession = when (variant.modeOf(DataService.MEDIA_SESSION)) {
                ServiceMode.DEMO -> DemoMediaSessionService(memoryService = memories, scope = graphScope)
                ServiceMode.REAL -> throw RealServicesUnavailableException(DataService.MEDIA_SESSION)
            }
            var httpClient: HttpClient? = null
            val modelSetup = when (variant.modeOf(DataService.MODEL_SETUP)) {
                ServiceMode.DEMO -> DemoModelSetupService(
                    models = ModelCatalog.modelsForRam(device.totalMemoryBytes),
                )
                ServiceMode.REAL -> {
                    val client = createPlatformModelHttpClient()
                    httpClient = client
                    DownloadingModelSetupService(
                        totalRamBytes = device.totalMemoryBytes,
                        store = ModelFileStore(device.modelDirectory),
                        downloader = KtorModelDownloader(client),
                        scope = graphScope,
                    )
                }
            }
            return AppServices(
                variant = variant,
                modelSetup = modelSetup,
                memories = memories,
                mediaSession = mediaSession,
                graphJob = graphJob,
                httpClient = httpClient,
            )
        }
    }
}

class RealServicesUnavailableException(
    service: DataService,
) : IllegalStateException(
    "Real mode was requested for ${service.name}, but its production data service has not been implemented.",
)
