package com.example.p2psync

import android.net.wifi.p2p.WifiP2pDevice

data class P2PDevice(
    val name: String,
    val address: String,
    val status: Int,
    val isGroupOwner: Boolean = false
) {
    companion object {
        fun fromWifiP2pDevice(device: WifiP2pDevice): P2PDevice {
            return P2PDevice(
                name = device.deviceName ?: "Unknown Device",
                address = device.deviceAddress ?: "",
                status = device.status,
                isGroupOwner = false
            )
        }
    }
    
    fun getStatusString(): String {
        return when (status) {
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown"
        }
    }
}
