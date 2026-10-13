package org.deer.borrowai.data.app

import okio.Path

/** Platform facts the app graph needs. Injected so common code stays testable. */
interface DeviceEnvironment {
    /** Total RAM as reported by the OS, in bytes. */
    val totalMemoryBytes: Long

    /** App-private directory that holds downloaded models. */
    val modelDirectory: Path
}
