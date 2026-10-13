package com.niuma.core

data class NetworkState(val wifi: Boolean, val internet: Boolean, val validated: Boolean)

object NetworkReadiness {
    fun hasValidatedWifi(networks: Iterable<NetworkState>): Boolean = networks.any {
        it.wifi && it.internet && it.validated
    }
}
