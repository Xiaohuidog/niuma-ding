package com.niuma.checkin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.niuma.core.NetworkReadiness
import com.niuma.core.NetworkState

object DeviceNetwork {
    private fun state(capabilities: NetworkCapabilities?) = NetworkState(
        wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    )

    fun isValidatedWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return NetworkReadiness.hasValidatedWifi(connectivity.allNetworks.map {
            state(connectivity.getNetworkCapabilities(it))
        })
    }
}
