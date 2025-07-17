package com.example.p2psync.data

import android.net.wifi.p2p.WifiP2pDevice

/**
 * Data class representing a WiFi Direct P2P device
 */
data class P2PDevice(
    val deviceName: String,
    val deviceAddress: String,
    val status: Int,
    val isGroupOwner: Boolean = false,
    val primaryDeviceType: String = "",
    val secondaryDeviceType: String = ""
) {
    companion object {
        fun fromWifiP2pDevice(device: WifiP2pDevice): P2PDevice {
            return P2PDevice(
                deviceName = device.deviceName ?: "Unknown Device",
                deviceAddress = device.deviceAddress ?: "",
                status = device.status,
                primaryDeviceType = device.primaryDeviceType ?: "",
                secondaryDeviceType = device.secondaryDeviceType ?: ""
            )
        }
    }

    fun getStatusString(): String {
        return when (status) {
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown"
        }
    }

    fun isConnected(): Boolean = status == WifiP2pDevice.CONNECTED
    fun isAvailable(): Boolean = status == WifiP2pDevice.AVAILABLE
}
