package com.example.p2psync.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.p2psync.data.P2PDevice
import com.example.p2psync.data.TextMessage
import com.example.p2psync.network.P2PConnectionManager
import com.example.p2psync.network.P2PFileTransfer
import com.example.p2psync.network.P2PTextMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing P2P sync operations
 */
class P2PSyncViewModel(application: Application) : AndroidViewModel(application) {    private val context: Context = application.applicationContext
    private val connectionManager = P2PConnectionManager(context)
    private val fileTransfer = P2PFileTransfer()
    private val textMessaging = P2PTextMessaging()

    // Expose state flows from managers
    val isWifiP2pEnabled = connectionManager.isWifiP2pEnabled
    val discoveredDevices = connectionManager.discoveredDevices
    val connectionInfo = connectionManager.connectionInfo
    val thisDevice = connectionManager.thisDevice
    val isDiscovering = connectionManager.isDiscovering
    val connectionStatus = connectionManager.connectionStatus
    val transferProgress = fileTransfer.transferProgress
    val isTransferring = fileTransfer.isTransferring
    
    // Text messaging state flows
    val messages = textMessaging.messages
    val isListening = textMessaging.isListening
    val messagingConnectionStatus = textMessaging.connectionStatus

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to sync")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    init {
        checkPermissions()
        connectionManager.registerReceiver()
    }    override fun onCleared() {
        super.onCleared()
        connectionManager.unregisterReceiver()
        textMessaging.cleanup()
    }

    fun checkPermissions() {
        val requiredPermissions = mutableListOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val allGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        _permissionsGranted.value = allGranted
    }

    fun startDiscovery() {
        if (!_permissionsGranted.value) {
            _statusMessage.value = "Permissions not granted"
            return
        }

        viewModelScope.launch {
            _statusMessage.value = "Starting device discovery..."
            connectionManager.startDiscovery()
        }
    }

    fun stopDiscovery() {
        connectionManager.stopDiscovery()
        _statusMessage.value = "Discovery stopped"
    }

    fun connectToDevice(device: P2PDevice) {
        viewModelScope.launch {
            _statusMessage.value = "Connecting to ${device.deviceName}..."
            connectionManager.connectToDevice(device)
        }
    }

    fun disconnect() {
        connectionManager.disconnect()
        _statusMessage.value = "Disconnected"
    }

    fun updateStatusMessage(message: String) {
        _statusMessage.value = message
    }    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        return permissions.toTypedArray()
    }

    // Text messaging functions
    fun startMessageServer() {
        viewModelScope.launch {
            val result = textMessaging.startMessageServer()
            if (result.isSuccess) {
                _statusMessage.value = "Message server started"
            } else {
                _statusMessage.value = "Failed to start message server"
            }
        }
    }

    fun stopMessageServer() {
        textMessaging.stopMessageServer()
        _statusMessage.value = "Message server stopped"
    }    fun sendTextMessage(message: String, hostAddress: String) {
        val currentDevice = thisDevice.value
        if (currentDevice == null) {
            _statusMessage.value = "Device information not available"
            return
        }

        if (message.isBlank()) {
            _statusMessage.value = "Message cannot be empty"
            return
        }

        viewModelScope.launch {
            val result = textMessaging.sendTextMessage(
                message = message,
                hostAddress = hostAddress,
                senderName = currentDevice.deviceName,
                senderAddress = currentDevice.deviceAddress
            )

            if (result.isSuccess) {
                _statusMessage.value = "Message sent successfully"
            } else {
                _statusMessage.value = "Failed to send message: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    /**
     * Get the correct target IP address for messaging based on device role
     */
    fun getTargetAddress(): String? {
        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            return null
        }

        return if (connInfo.isGroupOwner) {
            // If this device is group owner, target should be client's IP
            // Get peer address from active connections
            textMessaging.getFirstPeerAddress()
        } else {
            // If this device is client, target should be group owner's IP
            connInfo.groupOwnerAddress?.hostAddress
        }
    }

    /**
     * Send text message with automatic target resolution
     */
    fun sendTextMessageAuto(message: String) {
        val targetAddress = getTargetAddress()
        if (targetAddress == null) {
            _statusMessage.value = "No target address available for messaging"
            return
        }
        
        sendTextMessage(message, targetAddress)
    }

    fun clearMessages() {
        textMessaging.clearMessages()
        _statusMessage.value = "Messages cleared"
    }

    fun getMessageCount(): Int = textMessaging.getMessageCount()
}
