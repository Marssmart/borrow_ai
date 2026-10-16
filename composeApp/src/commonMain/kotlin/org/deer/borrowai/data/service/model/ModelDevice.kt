package org.deer.borrowai.data.service.model

/** Device facts that model setup needs: installed RAM for tier choice and the current network. */
interface ModelDevice {
    val totalRamBytes: Long
    val network: ModelNetworkPolicy

    /** Releases platform listeners registered by [network]. The owner calls this once it is done with the device. */
    fun close() {}
}
