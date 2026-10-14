package org.deer.borrowai.data.service.model

import kotlinx.coroutines.flow.StateFlow

/** Platform-supplied network state that decides whether a model transfer may start without consent. */
interface ModelNetworkPolicy {
    /** True while the device is on an unmetered connection such as Wi-Fi or Ethernet. */
    val unmetered: StateFlow<Boolean>
}
