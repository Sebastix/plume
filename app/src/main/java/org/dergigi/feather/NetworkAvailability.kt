package org.dergigi.feather

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-lifetime connectivity observer; never probes a remote endpoint. */
internal class NetworkAvailability(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val available = MutableStateFlow(hasInternetNetwork())
    val online = available.asStateFlow()
    private fun hasInternetNetwork() = manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    private var defaultNetwork: Network? = manager.activeNetwork
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            available.value = manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == defaultNetwork) available.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        override fun onLost(network: Network) {
            if (network == defaultNetwork) {
                defaultNetwork = null
                available.value = false
            }
        }
    }
    init { manager.registerDefaultNetworkCallback(callback) }
}
