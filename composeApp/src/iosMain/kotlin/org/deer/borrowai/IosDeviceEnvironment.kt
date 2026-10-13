package org.deer.borrowai

import okio.Path
import org.deer.borrowai.data.app.DeviceEnvironment
import org.deer.borrowai.data.service.model.modelDirectoryUnder
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

class IosDeviceEnvironment : DeviceEnvironment {
    override val totalMemoryBytes: Long = NSProcessInfo.processInfo.physicalMemory.toLong()

    override val modelDirectory: Path = modelDirectoryUnder(applicationSupportPath())

    private fun applicationSupportPath(): String =
        NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
}
