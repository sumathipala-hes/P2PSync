package com.example.p2psync.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import com.example.p2psync.data.P2PDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages WiFi Direct P2P connections and device discovery
 */
class P2PConnectionManager(private val context: Context) : WiFiDirectBroadcastReceiver.WiFiDirectListener {

    companion object {
        private const val TAG = "P2PConnectionManager"
    }

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, context.mainLooper, null)
    private val receiver = WiFiDirectBroadcastReceiver(manager, channel, this)

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<P2PDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<P2PDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _thisDevice = MutableStateFlow<P2PDevice?>(null)
    val thisDevice: StateFlow<P2PDevice?> = _thisDevice.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    fun registerReceiver() {
        context.registerReceiver(receiver, intentFilter)
        Log.d(TAG, "WiFi Direct receiver registered")
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(receiver)
            Log.d(TAG, "WiFi Direct receiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was not registered", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!_isWifiP2pEnabled.value) {
            Log.w(TAG, "WiFi P2P not enabled")
            return
        }

        _isDiscovering.value = true
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery started successfully")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Discovery failed with reason: $reason")
                _isDiscovering.value = false
                // In emulator, simulate discovery success
                if (isEmulator()) {
                    simulateDeviceDiscovery()
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        _isDiscovering.value = false
        manager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery stopped successfully")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to stop discovery: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: P2PDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        _connectionStatus.value = "Connecting to ${device.deviceName}..."
        
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection initiated to ${device.deviceName}")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connection failed to ${device.deviceName}, reason: $reason")
                _connectionStatus.value = "Connection failed"
                
                // In emulator, simulate connection
                if (isEmulator()) {
                    simulateConnection(device)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group removed successfully")
                _connectionStatus.value = "Disconnected"
                _connectionInfo.value = null
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to remove group: $reason")
            }
        })
    }

    // WiFiDirectListener implementations
    override fun onWifiP2pStateChanged(enabled: Boolean) {
        _isWifiP2pEnabled.value = enabled
        Log.d(TAG, "WiFi P2P enabled: $enabled")
    }

    @SuppressLint("MissingPermission")
    override fun onPeersChanged() {
        manager?.requestPeers(channel) { peers: WifiP2pDeviceList ->
            val deviceList = peers.deviceList.map { P2PDevice.fromWifiP2pDevice(it) }
            _discoveredDevices.value = deviceList
            _isDiscovering.value = false
            Log.d(TAG, "Discovered ${deviceList.size} peers")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionChanged(networkInfo: NetworkInfo?) {
        if (networkInfo?.isConnected == true) {
            manager?.requestConnectionInfo(channel) { info ->
                _connectionInfo.value = info
                _connectionStatus.value = if (info.groupFormed) {
                    if (info.isGroupOwner) "Connected as Group Owner" else "Connected as Client"
                } else {
                    "Connected"
                }
                Log.d(TAG, "Connection info updated: groupOwner=${info.isGroupOwner}")
            }
        } else {
            _connectionInfo.value = null
            _connectionStatus.value = "Disconnected"
        }
    }    @SuppressLint("MissingPermission")
    override fun onThisDeviceChanged() {
        if (manager != null && channel != null) {
            manager.requestDeviceInfo(channel) { device ->
                device?.let {
                    _thisDevice.value = P2PDevice.fromWifiP2pDevice(it)
                    Log.d(TAG, "This device: ${it.deviceName}")
                }
            }
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
    }

    private fun simulateDeviceDiscovery() {
        Log.d(TAG, "Simulating device discovery for emulator")
        val mockDevices = listOf(
            P2PDevice("Android_Device_1", "02:00:00:00:00:01", WifiP2pDevice.AVAILABLE),
            P2PDevice("Android_Device_2", "02:00:00:00:00:02", WifiP2pDevice.AVAILABLE),
            P2PDevice("Android_Tablet", "02:00:00:00:00:03", WifiP2pDevice.AVAILABLE)
        )
        _discoveredDevices.value = mockDevices
        _isDiscovering.value = false
    }

    private fun simulateConnection(device: P2PDevice) {
        Log.d(TAG, "Simulating connection for emulator")
        _connectionStatus.value = "Connected to ${device.deviceName} (Simulated)"
    }
}
