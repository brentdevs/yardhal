package dev.brentdevs.yardhal

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper

class AndroidConnectivityObserver(
    context: Context,
    private val onConnectivityChanged: (available: Boolean, networkHandle: Long?) -> Unit,
) : AutoCloseable {
    private val connectivityManager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var network: Network? = null
    private var capabilities: NetworkCapabilities? = null
    private var linkProperties: LinkProperties? = null
    private var blocked = false
    private var publishedAvailable: Boolean? = null
    private var publishedHandle: Long? = null
    private var publishedTransportMask = 0
    private var publishedLinkProperties: LinkProperties? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (this@AndroidConnectivityObserver.network == network) return
            this@AndroidConnectivityObserver.network = network
            capabilities = null
            linkProperties = null
            blocked = false
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (this@AndroidConnectivityObserver.network != network) return
            capabilities = networkCapabilities
            publish()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (this@AndroidConnectivityObserver.network != network) return
            this@AndroidConnectivityObserver.linkProperties = linkProperties
            publish()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (this@AndroidConnectivityObserver.network != network) return
            this@AndroidConnectivityObserver.blocked = blocked
            publish()
        }

        override fun onLost(network: Network) {
            if (this@AndroidConnectivityObserver.network != network) return
            this@AndroidConnectivityObserver.network = null
            capabilities = null
            linkProperties = null
            blocked = false
            publish()
        }
    }

    fun start() {
        if (started) return
        connectivityManager.registerDefaultNetworkCallback(callback, handler)
        started = true
        network = connectivityManager.activeNetwork
        capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        linkProperties = network?.let(connectivityManager::getLinkProperties)
        publish()
    }

    override fun close() {
        if (!started) return
        connectivityManager.unregisterNetworkCallback(callback)
        started = false
    }

    private fun publish() {
        val currentCapabilities = capabilities
        val available = network != null && !blocked && currentCapabilities != null &&
            currentCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            currentCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            currentCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        val handle = network?.networkHandle
        var transportMask = 0
        val lastTransport = if (Build.VERSION.SDK_INT >= 35) NetworkCapabilities.TRANSPORT_SATELLITE else NetworkCapabilities.TRANSPORT_USB
        for (transport in NetworkCapabilities.TRANSPORT_CELLULAR..lastTransport) {
            if (currentCapabilities?.hasTransport(transport) == true) transportMask = transportMask or (1 shl transport)
        }
        if (publishedAvailable == available && publishedHandle == handle &&
            publishedTransportMask == transportMask && publishedLinkProperties == linkProperties
        ) return
        publishedAvailable = available
        publishedHandle = handle
        publishedTransportMask = transportMask
        publishedLinkProperties = linkProperties
        onConnectivityChanged(available, handle)
    }
}
