package com.andsi.airlyrics.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.andsi.airlyrics.lyrics.catalog.WifiCapabilityEvaluator
import com.andsi.airlyrics.lyrics.catalog.WifiSyncVerdict

internal class WifiNetworkBinder(
    context: Context
) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun select(): Selection {
        val network = connectivity.activeNetwork ?: return Selection(WifiSyncVerdict.UNAVAILABLE, null)
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return Selection(WifiSyncVerdict.UNAVAILABLE, null)
        val verdict = WifiCapabilityEvaluator.verdict(
            transports = transports(capabilities),
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )
        return Selection(
            verdict = verdict,
            network = network.takeIf { verdict == WifiSyncVerdict.WIFI }
        )
    }

    fun bindProcess(network: Network) {
        connectivity.bindProcessToNetwork(network)
    }

    fun unbindProcess() {
        connectivity.bindProcessToNetwork(null)
    }

    fun registerDefaultCallback(callback: ConnectivityManager.NetworkCallback) {
        connectivity.registerDefaultNetworkCallback(callback)
    }

    fun unregister(callback: ConnectivityManager.NetworkCallback) {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    data class Selection(
        val verdict: WifiSyncVerdict,
        val network: Network?
    )

    companion object {
        fun transports(capabilities: NetworkCapabilities): Set<String> {
            val values = mutableSetOf<String>()
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) values += "WIFI"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) values += "CELLULAR"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) values += "VPN"
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) values += "ETHERNET"
            return values
        }
    }
}
