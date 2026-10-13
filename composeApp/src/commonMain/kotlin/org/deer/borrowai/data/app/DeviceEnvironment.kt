package org.deer.borrowai.data.app

import kotlinx.coroutines.CoroutineScope
import okio.Path
import org.deer.borrowai.data.service.ModelSetupService

/** Platform facts the app graph needs. Injected so common code stays testable. */
interface DeviceEnvironment {
    /** Total RAM as reported by the OS, in bytes. */
    val totalMemoryBytes: Long

    /** App-private directory that holds downloaded models. */
    val modelDirectory: Path

    /** The platform's real model setup. [RealModelSetup.onClose] runs when the service graph closes. */
    fun createRealModelSetup(scope: CoroutineScope): RealModelSetup
}

class RealModelSetup(
    val service: ModelSetupService,
    val onClose: () -> Unit = {},
)
