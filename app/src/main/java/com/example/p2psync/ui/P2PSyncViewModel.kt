package com.example.p2psync.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.p2psync.data.P2PDevice
import com.example.p2psync.data.FileMessage
import com.example.p2psync.network.P2PConnectionManager
import com.example.p2psync.network.P2PFileTransfer
import com.example.p2psync.network.P2PFileMessaging
import java.io.File
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
    private val fileMessaging = P2PFileMessaging(context)

    // Expose state flows from managers
    val isWifiP2pEnabled = connectionManager.isWifiP2pEnabled
    val discoveredDevices = connectionManager.discoveredDevices
    val connectionInfo = connectionManager.connectionInfo
    val thisDevice = connectionManager.thisDevice
    val isDiscovering = connectionManager.isDiscovering
    val connectionStatus = connectionManager.connectionStatus
    val transferProgress = fileTransfer.transferProgress
    val isTransferring = fileTransfer.isTransferring
    
    // File messaging state flows
    val fileMessages = fileMessaging.fileMessages
    val isListening = fileMessaging.isListening
    val messagingConnectionStatus = fileMessaging.connectionStatus
    val fileTransferProgress = fileMessaging.transferProgress

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
        fileMessaging.cleanup()
    }

    fun checkPermissions() {
        // Only check WiFi Direct related permissions for the main app functionality
        val wifiDirectPermissions = mutableListOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiDirectPermissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val allGranted = wifiDirectPermissions.all { permission ->
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
        // Only request WiFi Direct permissions initially
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

    fun getFilePermissions(): Array<String> {
        // Separate method for file permissions
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        return permissions.toTypedArray()
    }

    fun checkFilePermissions(): Boolean {
        // For Android 13+, we use scoped storage which doesn't require these permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        val filePermissions = getFilePermissions()
        return filePermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    // File messaging functions
    fun startFileServer() {
        viewModelScope.launch {
            val result = fileMessaging.startFileServer()
            if (result.isSuccess) {
                _statusMessage.value = "File server started"
            } else {
                _statusMessage.value = "Failed to start file server"
            }
        }
    }

    fun stopFileServer() {
        fileMessaging.stopFileServer()
        _statusMessage.value = "File server stopped"
    }    fun sendFile(file: File, hostAddress: String) {
        val currentDevice = thisDevice.value
        if (currentDevice == null) {
            _statusMessage.value = "Device information not available"
            return
        }

        if (!file.exists()) {
            _statusMessage.value = "File does not exist"
            return
        }

        viewModelScope.launch {
            val result = fileMessaging.sendFile(
                file = file,
                hostAddress = hostAddress,
                senderName = currentDevice.deviceName,
                senderAddress = currentDevice.deviceAddress
            )

            if (result.isSuccess) {
                _statusMessage.value = "File sent successfully"
            } else {
                _statusMessage.value = "Failed to send file: ${result.exceptionOrNull()?.message}"
            }
        }
    }    /**
     * Get the correct target information for file sharing based on device role
     */
    fun getTargetAddress(): String? {
        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            return null
        }

        return if (connInfo.isGroupOwner) {
            // If this device is group owner, files can be sent to any connected client
            "Available for file sharing"
        } else {
            // If this device is client, show group owner's IP
            connInfo.groupOwnerAddress?.hostAddress
        }
    }/**
     * Send file with automatic target resolution
     */
    fun sendFileAuto(file: File) {
        val currentDevice = thisDevice.value
        if (currentDevice == null) {
            _statusMessage.value = "Device information not available"
            return
        }

        if (!file.exists()) {
            _statusMessage.value = "File does not exist"
            return
        }

        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            _statusMessage.value = "No P2P connection available"
            return
        }

        viewModelScope.launch {
            val result = if (connInfo.isGroupOwner) {
                // Group owner: currently just indicates ready to receive files
                _statusMessage.value = "Ready to receive files from clients"
                Result.success(Unit)
            } else {
                // Client: send to group owner
                val targetAddress = connInfo.groupOwnerAddress?.hostAddress
                if (targetAddress == null) {
                    _statusMessage.value = "Group owner address not available"
                    return@launch
                }
                
                fileMessaging.sendFile(
                    file = file,
                    hostAddress = targetAddress,
                    senderName = currentDevice.deviceName,
                    senderAddress = currentDevice.deviceAddress
                )
            }

            if (result.isSuccess && !connInfo.isGroupOwner) {
                _statusMessage.value = "File sent to group owner"
            } else if (result.isFailure) {
                _statusMessage.value = "Failed to send file: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    /**
     * Check if this device is the group owner
     */
    fun isGroupOwner(): Boolean {
        return connectionInfo.value?.isGroupOwner == true
    }

    fun clearFileMessages() {
        fileMessaging.clearFileMessages()
        _statusMessage.value = "File messages cleared"
    }

    fun getFileMessageCount(): Int = fileMessages.value.size

    /**
     * Open a received file
     */
    fun openFile(fileMessage: FileMessage) {
        if (!fileMessage.canBeOpened()) {
            _statusMessage.value = "File is not available to open"
            return
        }

        viewModelScope.launch {
            try {
                val file = File(fileMessage.filePath)
                if (!file.exists()) {
                    _statusMessage.value = "File not found: ${fileMessage.fileName}"
                    return@launch
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "com.example.p2psync.fileprovider",
                        file
                    )
                    setDataAndType(uri, fileMessage.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(intent)
                    _statusMessage.value = "Opening ${fileMessage.fileName}"
                } catch (e: Exception) {
                    // Fallback: try with generic intent
                    val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "com.example.p2psync.fileprovider",
                            file
                        )
                        setDataAndType(uri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    
                    try {
                        context.startActivity(genericIntent)
                        _statusMessage.value = "Opening ${fileMessage.fileName}"
                    } catch (e2: Exception) {
                        _statusMessage.value = "No app available to open this file type"
                    }
                }
            } catch (e: Exception) {
                _statusMessage.value = "Error opening file: ${e.message}"
            }
        }
    }
}
