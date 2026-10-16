package org.deer.borrowai.data.service.model

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidModelDevice(context: Context) : ModelDevice {
    private val appContext = context.applicationContext

    override val totalRamBytes: Long = ActivityManager.MemoryInfo().also {
        appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem

    private val networkPolicy = AndroidModelNetworkPolicy(appContext)

    override val network: ModelNetworkPolicy = networkPolicy

    override fun close() = networkPolicy.close()
}

/** Tracks whether the default network is unmetered, so model downloads can wait for Wi-Fi. */
private class AndroidModelNetworkPolicy(context: Context) : ModelNetworkPolicy {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(currentlyUnmetered())
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            state.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        override fun onLost(network: Network) {
            state.value = false
        }
    }

    override val unmetered: StateFlow<Boolean> = state

    init {
        connectivity.registerDefaultNetworkCallback(callback)
    }

    fun close() {
        connectivity.unregisterNetworkCallback(callback)
    }
    private fun currentlyUnmetered(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
