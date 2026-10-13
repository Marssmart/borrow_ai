package org.deer.borrowai.data.app

import okio.Path
import okio.Path.Companion.toPath

class TestDeviceEnvironment(
    override val totalMemoryBytes: Long = 8L * 1024L * 1024L * 1024L,
    override val modelDirectory: Path = "/test-app/models".toPath(),
) : DeviceEnvironment
