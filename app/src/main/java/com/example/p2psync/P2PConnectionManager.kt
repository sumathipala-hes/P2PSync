package com.example.p2psync

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

class P2PConnectionManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) {
    
    interface ConnectionListener {
        fun onDeviceListUpdated(devices: List<P2PDevice>)
        fun onConnectionEstablished(info: WifiP2pInfo)
        fun onConnectionFailed(reason: String)
        fun onDiscoveryStarted()
        fun onDiscoveryStopped()
    }
    
    private var connectionListener: ConnectionListener? = null
    private val discoveredDevices = mutableListOf<P2PDevice>()
    
    fun setConnectionListener(listener: ConnectionListener) {
        this.connectionListener = listener
    }
    
    fun startDiscovery() {
        Log.d("P2PSync", "Starting peer discovery")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("P2PSync", "Discovery initiated successfully")
                connectionListener?.onDiscoveryStarted()
            }
            
            override fun onFailure(reason: Int) {
                Log.e("P2PSync", "Discovery failed: $reason")
                connectionListener?.onConnectionFailed("Discovery failed: $reason")
            }
        })
    }
    
    fun stopDiscovery() {
        Log.d("P2PSync", "Stopping peer discovery")
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("P2PSync", "Discovery stopped successfully")
                connectionListener?.onDiscoveryStopped()
            }
            
            override fun onFailure(reason: Int) {
                Log.e("P2PSync", "Failed to stop discovery: $reason")
            }
        })
    }
    
    fun updatePeerList(peers: WifiP2pDeviceList) {
        discoveredDevices.clear()
        for (device in peers.deviceList) {
            discoveredDevices.add(P2PDevice.fromWifiP2pDevice(device))
        }
        Log.d("P2PSync", "Updated peer list: ${discoveredDevices.size} devices found")
        connectionListener?.onDeviceListUpdated(discoveredDevices.toList())
    }
    
    fun connectToDevice(device: P2PDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.address
        }
        
        Log.d("P2PSync", "Attempting to connect to ${device.name} (${device.address})")
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("P2PSync", "Connection request sent successfully")
                // Connection info will be received through broadcast receiver
            }
            
            override fun onFailure(reason: Int) {
                Log.e("P2PSync", "Connection failed: $reason")
                connectionListener?.onConnectionFailed("Connection failed: $reason")
            }
        })
    }
    
    fun disconnect() {
        Log.d("P2PSync", "Disconnecting from group")
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("P2PSync", "Disconnected successfully")
            }
            
            override fun onFailure(reason: Int) {
                Log.e("P2PSync", "Failed to disconnect: $reason")
            }
        })
    }
    
    fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info ->
            if (info != null) {
                Log.d("P2PSync", "Connection info received: ${info.groupFormed}")
                connectionListener?.onConnectionEstablished(info)
            }
        }
    }
    
    fun getDiscoveredDevices(): List<P2PDevice> {
        return discoveredDevices.toList()
    }
}
