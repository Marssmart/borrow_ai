package org.deer.borrowai.data.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import okio.Path
import okio.Path.Companion.toPath
import org.deer.borrowai.data.service.model.DownloadingModelSetupService
import org.deer.borrowai.data.service.model.KtorModelDownloader
import org.deer.borrowai.data.service.model.ModelFileStore

class TestDeviceEnvironment(
    override val totalMemoryBytes: Long = 8L * 1024L * 1024L * 1024L,
    override val modelDirectory: Path = "/test-app/models".toPath(),
) : DeviceEnvironment {
    override fun createRealModelSetup(scope: CoroutineScope): RealModelSetup {
        val client = HttpClient(MockEngine { respond(content = ByteArray(0)) })
        return RealModelSetup(
            service = DownloadingModelSetupService(
                totalRamBytes = totalMemoryBytes,
                store = ModelFileStore(modelDirectory),
                downloader = KtorModelDownloader(client),
                scope = scope,
            ),
            onClose = { client.close() },
        )
    }
}
