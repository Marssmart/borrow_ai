package org.deer.borrowai.data.service.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSProcessInfo
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

class IosModelDevice : ModelDevice {
    override val totalRamBytes: Long = NSProcessInfo.processInfo.physicalMemory.toLong()

    private val networkPolicy = IosModelNetworkPolicy()

    override val network: ModelNetworkPolicy = networkPolicy

    override fun close() = networkPolicy.close()
}

/** Tracks whether the system path is satisfied and not expensive (not cellular or Personal Hotspot). */
@OptIn(ExperimentalForeignApi::class)
private class IosModelNetworkPolicy : ModelNetworkPolicy {
    private val state = MutableStateFlow(false)
    private val monitor = nw_path_monitor_create()

    override val unmetered: StateFlow<Boolean> = state

    init {
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_set_update_handler(monitor) { path ->
            state.value = path != null &&
                nw_path_get_status(path) == nw_path_status_satisfied &&
                !nw_path_is_expensive(path)
        }
        nw_path_monitor_start(monitor)
    }

    fun close() {
        nw_path_monitor_cancel(monitor)
    }
}
