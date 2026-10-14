package org.deer.borrowai

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import okio.Path
import org.deer.borrowai.data.app.DeviceEnvironment
import org.deer.borrowai.data.app.RealModelSetup
import org.deer.borrowai.data.service.model.DownloadingModelSetupService
import org.deer.borrowai.data.service.model.KtorModelDownloader
import org.deer.borrowai.data.service.model.ModelFileStore
import org.deer.borrowai.data.service.model.createPlatformModelHttpClient
import org.deer.borrowai.data.service.model.modelDirectoryUnder
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
class IosDeviceEnvironment : DeviceEnvironment {
    override val totalMemoryBytes: Long = NSProcessInfo.processInfo.physicalMemory.toLong()

    override val modelDirectory: Path = modelDirectoryUnder(applicationSupportPath()).also { path ->
        excludeFromBackup(path.toString())
    }

    override fun createRealModelSetup(scope: CoroutineScope): RealModelSetup {
        val client = createPlatformModelHttpClient()
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

    private fun applicationSupportPath(): String =
        NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String

    /** Models are large and re-downloadable, so keep them out of iCloud and iTunes backups. */
    private fun excludeFromBackup(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        NSURL.fileURLWithPath(path).setResourceValue(
            NSNumber(bool = true),
            forKey = NSURLIsExcludedFromBackupKey,
            error = null,
        )
    }
}
