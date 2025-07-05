package com.example.p2psync.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.p2psync.data.P2PDevice
import com.example.p2psync.data.FileMessage
import com.example.p2psync.network.P2PConnectionManager
import com.example.p2psync.network.P2PFileTransfer
import com.example.p2psync.network.P2PFileMessaging
import com.example.p2psync.utils.FolderUtils
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * ViewModel for managing P2P sync operations
 */
class P2PSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context = application.applicationContext
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
    val connectedClientsInfo = fileMessaging.connectedClientsInfo

    // Transfer mode state for bidirectional file sharing
    private val _transferMode = MutableStateFlow("none") // "send", "receive", "none"
    val transferMode: StateFlow<String> = _transferMode.asStateFlow()

    private val _peerTransferMode = MutableStateFlow("none") // Track peer's mode
    val peerTransferMode: StateFlow<String> = _peerTransferMode.asStateFlow()

    // Folder sharing state
    private val _folderTransferMode = MutableStateFlow("none") // "send", "receive", "none"
    val folderTransferMode: StateFlow<String> = _folderTransferMode.asStateFlow()

    private val _selectedSendFolder = MutableStateFlow<File?>(null)
    val selectedSendFolder: StateFlow<File?> = _selectedSendFolder.asStateFlow()

    private val _selectedSendFolderUri = MutableStateFlow<Uri?>(null)
    val selectedSendFolderUri: StateFlow<Uri?> = _selectedSendFolderUri.asStateFlow()

    private val _selectedReceiveFolder = MutableStateFlow<File?>(null)
    val selectedReceiveFolder: StateFlow<File?> = _selectedReceiveFolder.asStateFlow()

    private val _selectedReceiveFolderUri = MutableStateFlow<Uri?>(null)
    val selectedReceiveFolderUri: StateFlow<Uri?> = _selectedReceiveFolderUri.asStateFlow()

    private val _folderTransferProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderTransferProgress: StateFlow<Map<String, Int>> = _folderTransferProgress.asStateFlow()

    private val _isFolderTransferring = MutableStateFlow(false)
    val isFolderTransferring: StateFlow<Boolean> = _isFolderTransferring.asStateFlow()

    private val _folderTransferStatus = MutableStateFlow("")
    val folderTransferStatus: StateFlow<String> = _folderTransferStatus.asStateFlow()

    // Folder sync state
    private val _isSyncMode = MutableStateFlow(false)
    val isSyncMode: StateFlow<Boolean> = _isSyncMode.asStateFlow()

    private val _syncProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val syncProgress: StateFlow<Map<String, Int>> = _syncProgress.asStateFlow()

    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _filesToSync = MutableStateFlow<List<String>>(emptyList())
    val filesToSync: StateFlow<List<String>> = _filesToSync.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to sync")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    init {
        checkPermissions()
        connectionManager.registerReceiver()
        
        // Observe connection changes to detect clients or send hello
        viewModelScope.launch {
            connectionInfo.collect { connInfo ->
                if (connInfo?.groupFormed == true) {
                    if (connInfo.isGroupOwner) {
                        Log.d("P2PSyncViewModel", "Group formed as owner, detecting potential clients...")
                        delay(2000) // Wait a bit for clients to settle
                        detectPotentialClients()
                    } else {
                        Log.d("P2PSyncViewModel", "Connected as client, sending CLIENT_HELLO...")
                        delay(1000) // Wait a bit for connection to stabilize
                        sendClientHello()
                    }
                }
            }
        }
        
        // Debug: Log when connected clients change
        viewModelScope.launch {
            connectedClientsInfo.collect { clients ->
                Log.d("P2PSyncViewModel", "=== Connected Clients Changed ===")
                Log.d("P2PSyncViewModel", "New client list: $clients")
                Log.d("P2PSyncViewModel", "Client count: ${clients.size}")
                Log.d("P2PSyncViewModel", "=== End Client Change ===")
            }
        }
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

    // Transfer mode management functions
    fun setTransferMode(mode: String) {
        _transferMode.value = mode
        // Notify peer about our mode change via file messaging
        notifyPeerModeChange(mode)
        _statusMessage.value = when (mode) {
            "send" -> "Ready to send files"
            "receive" -> "Ready to receive files"
            else -> "Transfer mode cleared"
        }
    }

    fun setSendMode() {
        setTransferMode("send")
    }

    fun setReceiveMode() {
        setTransferMode("receive")
    }

    private fun notifyPeerModeChange(mode: String) {
        // This could be implemented to send mode change notifications
        // For now, we'll handle mode coordination through UI coordination
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
    }
    
    /**
     * Send file to a specific host address (manual targeting)
     * Note: For automatic bidirectional sending, use sendFileAuto() instead
     */
    fun sendFile(file: File, hostAddress: String) {
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
    }    /**
     * Send file with bidirectional support
     */
    fun sendFileAuto(file: File) {
        Log.d("P2PSyncViewModel", "sendFileAuto called with file: ${file.name}")
        
        val currentDevice = thisDevice.value
        if (currentDevice == null) {
            Log.w("P2PSyncViewModel", "Device information not available")
            _statusMessage.value = "Device information not available"
            return
        }

        if (!file.exists()) {
            Log.w("P2PSyncViewModel", "File does not exist: ${file.absolutePath}")
            _statusMessage.value = "File does not exist"
            return
        }

        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            Log.w("P2PSyncViewModel", "No P2P connection available")
            _statusMessage.value = "No P2P connection available"
            return
        }

        // Check if we're in send mode
        if (_transferMode.value != "send") {
            Log.w("P2PSyncViewModel", "Not in send mode, current mode: ${_transferMode.value}")
            _statusMessage.value = "Please select 'Send Mode' first"
            return
        }

        Log.d("P2PSyncViewModel", "Connection info - isGroupOwner: ${connInfo.isGroupOwner}")

        viewModelScope.launch {
            if (connInfo.isGroupOwner) {
                Log.d("P2PSyncViewModel", "Device is group owner, broadcasting file to all clients")
                // Group owner broadcasts to all connected clients
                val result = fileMessaging.sendFileToAllClients(
                    file = file,
                    senderName = currentDevice.deviceName,
                    senderAddress = currentDevice.deviceAddress
                )

                if (result.isSuccess) {
                    val sentMessages = result.getOrNull() ?: emptyList()
                    Log.d("P2PSyncViewModel", "File broadcast successfully to ${sentMessages.size} clients")
                    _statusMessage.value = "File sent to ${sentMessages.size} client(s)"
                } else {
                    Log.e("P2PSyncViewModel", "Failed to broadcast file: ${result.exceptionOrNull()?.message}")
                    _statusMessage.value = "Failed to send file: ${result.exceptionOrNull()?.message}"
                }
            } else {
                Log.d("P2PSyncViewModel", "Device is client, sending to group owner only")
                // Client sends only to group owner
                val groupOwnerAddress = connInfo.groupOwnerAddress?.hostAddress
                
                if (groupOwnerAddress == null) {
                    Log.w("P2PSyncViewModel", "Group owner address not available")
                    _statusMessage.value = "Group owner address not available"
                    return@launch
                }

                Log.d("P2PSyncViewModel", "Sending file to group owner: $groupOwnerAddress")

                val result = fileMessaging.sendFile(
                    file = file,
                    hostAddress = groupOwnerAddress,
                    senderName = currentDevice.deviceName,
                    senderAddress = currentDevice.deviceAddress
                )

                if (result.isSuccess) {
                    Log.d("P2PSyncViewModel", "File sent successfully to group owner")
                    _statusMessage.value = "File sent successfully to group owner"
                } else {
                    Log.e("P2PSyncViewModel", "Failed to send file to group owner: ${result.exceptionOrNull()?.message}")
                    _statusMessage.value = "Failed to send file: ${result.exceptionOrNull()?.message}"
                }
            }
            
            // Reset to neutral mode after send attempt
            _transferMode.value = "none"
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

    /**
     * Get count of connected clients
     */
    fun getConnectedClientCount(): Int {
        return fileMessaging.getConnectedClients().size
    }

    /**
     * Get list of connected client IPs (for group owner)
     */
    fun getConnectedClientIPs(): List<String> {
        return fileMessaging.getAllActiveClientIPs()
    }

    /**
     * Get detailed client information for UI display
     */
    fun getConnectedClientsInfo(): List<String> {
        val connectedClients = fileMessaging.getConnectedClients()
        return connectedClients.map { clientAddress ->
            // Parse IP from socket address format: /192.168.1.100:12345
            val ip = clientAddress.substringAfter("/").substringBefore(":")
            ip
        }.distinct()
    }

    /**
     * Check if device can send files (has target available)
     */
    fun canSendFiles(): Boolean {
        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) return false
        
        return if (connInfo.isGroupOwner) {
            // Group owner can send if there are connected clients
            getConnectedClientCount() > 0
        } else {
            // Client can always send to group owner
            true
        }
    }

    /**
     * Check if device can receive files
     */
    fun canReceiveFiles(): Boolean {
        val connInfo = connectionInfo.value
        return connInfo?.groupFormed == true && isListening.value
    }

    /**
     * Send file to all connected clients (group owner only)
     */
    fun sendFileToAllClients(file: File) {
        Log.d("P2PSyncViewModel", "sendFileToAllClients called with file: ${file.name}")
        
        val currentDevice = thisDevice.value
        if (currentDevice == null) {
            Log.w("P2PSyncViewModel", "Device information not available")
            _statusMessage.value = "Device information not available"
            return
        }

        if (!file.exists()) {
            Log.w("P2PSyncViewModel", "File does not exist: ${file.absolutePath}")
            _statusMessage.value = "File does not exist"
            return
        }

        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            Log.w("P2PSyncViewModel", "No P2P connection available")
            _statusMessage.value = "No P2P connection available"
            return
        }

        if (!connInfo.isGroupOwner) {
            Log.w("P2PSyncViewModel", "Only group owner can send to all clients")
            _statusMessage.value = "Only group owner can send to all clients"
            return
        }

        Log.d("P2PSyncViewModel", "Group owner sending file to all clients")

        viewModelScope.launch {
            val result = fileMessaging.sendFileToAllClients(
                file = file,
                senderName = currentDevice.deviceName,
                senderAddress = currentDevice.deviceAddress
            )

            if (result.isSuccess) {
                val sentMessages = result.getOrNull() ?: emptyList()
                Log.d("P2PSyncViewModel", "File broadcast successfully to ${sentMessages.size} clients")
                _statusMessage.value = "File sent to ${sentMessages.size} client(s)"
            } else {
                Log.e("P2PSyncViewModel", "Failed to broadcast file: ${result.exceptionOrNull()?.message}")
                _statusMessage.value = "Failed to send file: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    /**
     * Manually detect and add potential clients based on WiFi Direct connection
     * This helps show clients even before they connect to the file server
     */
    fun detectPotentialClients() {
        viewModelScope.launch {
            val connInfo = connectionInfo.value
            if (connInfo?.isGroupOwner == true && connInfo.groupFormed) {
                Log.d("P2PSyncViewModel", "Attempting to detect potential clients...")
                
                // In WiFi Direct, the group owner is typically 192.168.49.1
                // and clients are typically 192.168.49.x where x > 1
                val groupOwnerIP = connInfo.groupOwnerAddress?.hostAddress
                Log.d("P2PSyncViewModel", "Group owner IP: $groupOwnerIP")
                
                if (groupOwnerIP != null && groupOwnerIP.startsWith("192.168.49.")) {
                    // Scan for potential clients in the WiFi Direct network
                    fileMessaging.detectPotentialClients(groupOwnerIP)
                }
            }
        }
    }

    /**
     * Manually refresh the connected clients list
     * This can help detect clients that may not have appeared automatically
     */
    fun refreshConnectedClients() {
        viewModelScope.launch {
            val connInfo = connectionInfo.value
            if (connInfo?.isGroupOwner == true && connInfo.groupFormed) {
                Log.d("P2PSyncViewModel", "Manually refreshing connected clients...")
                _statusMessage.value = "Refreshing client connections..."
                
                // Log current state
                val currentClients = fileMessaging.getConnectedClients()
                Log.d("P2PSyncViewModel", "Current tracked clients: $currentClients")
                
                // Trigger detection
                detectPotentialClients()
                
                // Update status message
                kotlinx.coroutines.delay(1000)
                val clientCount = fileMessaging.getAllActiveClientIPs().size
                _statusMessage.value = if (clientCount > 0) {
                    "Found $clientCount connected client(s)"
                } else {
                    "No clients detected - they will appear when sending files"
                }
            } else {
                _statusMessage.value = "Not group owner or no WiFi Direct connection"
            }
        }
    }

    /**
     * Debug method to check current client state
     */
    fun debugClientState(): String {
        val connInfo = connectionInfo.value
        val isGroupOwner = connInfo?.isGroupOwner == true
        val isGroupFormed = connInfo?.groupFormed == true
        val connectedFromFlow = connectedClientsInfo.value
        val connectedFromMethod = getConnectedClientsInfo()
        val rawClients = fileMessaging.getConnectedClients()
        
        return buildString {
            appendLine("=== DEBUG CLIENT STATE ===")
            appendLine("Is Group Owner: $isGroupOwner")
            appendLine("Group Formed: $isGroupFormed")
            appendLine("Clients from StateFlow: $connectedFromFlow")
            appendLine("Clients from Method: $connectedFromMethod")
            appendLine("Raw Connected Clients: $rawClients")
            appendLine("=== END DEBUG ===")
        }
    }

    /**
     * Send client hello signal to announce presence to group owner
     * This makes the client appear in the group owner's list immediately
     */
    fun sendClientHello() {
        viewModelScope.launch {
            val connInfo = connectionInfo.value
            if (connInfo?.groupFormed == true && !connInfo.isGroupOwner) {
                val groupOwnerAddress = connInfo.groupOwnerAddress?.hostAddress
                if (groupOwnerAddress != null) {
                    Log.d("P2PSyncViewModel", "Sending CLIENT_HELLO to group owner: $groupOwnerAddress")
                    
                    val result = fileMessaging.sendClientHello(groupOwnerAddress)
                    if (result.isSuccess) {
                        Log.d("P2PSyncViewModel", "CLIENT_HELLO sent successfully")
                        _statusMessage.value = "Connected to group owner"
                    } else {
                        Log.w("P2PSyncViewModel", "Failed to send CLIENT_HELLO: ${result.exceptionOrNull()?.message}")
                        _statusMessage.value = "Connection established (hello failed)"
                    }
                } else {
                    Log.w("P2PSyncViewModel", "Group owner address not available for CLIENT_HELLO")
                }
            }
        }
    }

    /**
     * Enhanced client detection specifically for folder sharing
     */
    fun detectClientsForFolderSharing() {
        viewModelScope.launch {
            val connInfo = connectionInfo.value
            if (connInfo?.isGroupOwner == true && connInfo.groupFormed) {
                Log.d("P2PSyncViewModel", "Detecting clients for folder sharing...")
                _folderTransferStatus.value = "Detecting connected clients..."
                
                // Trigger multiple detection methods
                detectPotentialClients()
                delay(1000)
                refreshConnectedClients()
                
                // Update status based on results
                delay(1000)
                val clientCount = fileMessaging.getAllActiveClientIPs().size
                _folderTransferStatus.value = if (clientCount > 0) {
                    "Ready for folder sharing - ${clientCount} client(s) detected"
                } else {
                    "Ready for folder sharing - Waiting for clients to connect"
                }
            } else if (connInfo?.groupFormed == true && !connInfo.isGroupOwner) {
                Log.d("P2PSyncViewModel", "Announcing presence for folder sharing...")
                _folderTransferStatus.value = "Announcing presence to group owner..."
                
                sendClientHello()
                delay(1000)
                _folderTransferStatus.value = "Ready for folder sharing - Connected to group owner"
            }
        }
    }

    // Folder sharing functions
    
    /**
     * Set folder transfer mode for sending or receiving folders
     */
    fun setFolderTransferMode(mode: String) {
        _folderTransferMode.value = mode
        _folderTransferStatus.value = when (mode) {
            "send" -> "Ready to send folder - Select a folder to share"
            "receive" -> "Ready to receive folder - Select destination folder"
            else -> "Folder transfer mode cleared"
        }
        
        // Trigger enhanced client detection/notification when entering folder mode
        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed == true && mode != "none") {
            viewModelScope.launch {
                // Small delay to ensure UI has updated
                delay(500)
                detectClientsForFolderSharing()
            }
        }
    }

    /**
     * Set send folder path
     */
    fun setSelectedSendFolder(folder: File?) {
        _selectedSendFolder.value = folder
        _selectedSendFolderUri.value = null // Clear URI when setting File
        if (folder != null) {
            _folderTransferStatus.value = "Selected folder: ${folder.name} (${getFileCount(folder)} files)"
        }
    }

    /**
     * Set send folder URI (for storage access framework)
     */
    fun setSelectedSendFolderUri(folderUri: Uri?, folderName: String? = null) {
        _selectedSendFolderUri.value = folderUri
        _selectedSendFolder.value = null // Clear File when setting URI
        if (folderUri != null) {
            val displayName = folderName ?: "Selected Folder"
            try {
                val fileCount = fileMessaging.getFileListFromFolderUri(folderUri).size
                _folderTransferStatus.value = "Selected folder: $displayName ($fileCount files)"
            } catch (e: Exception) {
                _folderTransferStatus.value = "Selected folder: $displayName"
            }
        }
    }

    /**
     * Set receive folder path and URI
     */
    fun setSelectedReceiveFolder(folder: File?, uri: Uri?) {
        _selectedReceiveFolder.value = folder
        _selectedReceiveFolderUri.value = uri
        if (folder != null || uri != null) {
            val folderPath = folder?.absolutePath ?: uri?.let { 
                getAbsolutePathFromUri(it)
            } ?: "Selected Folder"
            _folderTransferStatus.value = "Receive destination: $folderPath"
            // Set the custom receive directory URI in the file messaging service
            fileMessaging.setCustomReceiveDirectoryUri(uri)
        } else {
            // Clear custom receive directory
            fileMessaging.setCustomReceiveDirectoryUri(null)
        }
    }

    /**
     * Get absolute path from URI for display
     */
    private fun getAbsolutePathFromUri(uri: Uri): String {
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            val folderName = documentFile?.name ?: "Selected Folder"
            
            // Try to extract readable path from URI
            val uriPath = uri.path
            when {
                uriPath?.contains("/tree/primary:") == true -> {
                    val relativePath = uriPath.substringAfter("/tree/primary:")
                    "/storage/emulated/0/$relativePath"
                }
                uriPath?.contains("/tree/") == true -> {
                    val pathPart = uriPath.substringAfter("/tree/")
                    "/storage/emulated/0/$pathPart"
                }
                else -> folderName
            }
        } catch (e: Exception) {
            Log.w("P2PSyncViewModel", "Error getting absolute path from URI: ${e.message}")
            "Selected Folder"
        }
    }

    /**
     * Send entire folder to connected devices
     */
    fun sendFolder() {
        val sendFolder = _selectedSendFolder.value
        val sendFolderUri = _selectedSendFolderUri.value
        
        if (sendFolder == null && sendFolderUri == null) {
            _folderTransferStatus.value = "Please select a folder to send"
            return
        }

        if (_folderTransferMode.value != "send") {
            _folderTransferStatus.value = "Please enable send mode first"
            return
        }

        val currentDevice = thisDevice.value
        if (currentDevice == null) {
            _folderTransferStatus.value = "Device information not available"
            return
        }

        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            _folderTransferStatus.value = "No P2P connection available"
            return
        }

        viewModelScope.launch {
            _isFolderTransferring.value = true
            _folderTransferStatus.value = "Scanning folder..."

            try {
                val files = if (sendFolder != null) {
                    getAllFilesInFolder(sendFolder)
                } else {
                    // For URI-based folders, we need to get files differently
                    getAllFilesInFolderUri(sendFolderUri!!)
                }
                
                if (files.isEmpty()) {
                    _folderTransferStatus.value = "No files found in selected folder"
                    _isFolderTransferring.value = false
                    return@launch
                }

                _folderTransferStatus.value = "Sending ${files.size} files..."
                
                var successCount = 0
                var failCount = 0

                files.forEachIndexed { index, file ->
                    val progress = ((index + 1) * 100) / files.size
                    _folderTransferProgress.value = mapOf("overall" to progress)
                    _folderTransferStatus.value = "Sending ${file.name} (${index + 1}/${files.size})"

                    val result = if (connInfo.isGroupOwner) {
                        fileMessaging.sendFileToAllClients(
                            file = file,
                            senderName = currentDevice.deviceName,
                            senderAddress = currentDevice.deviceAddress
                        )
                    } else {
                        val groupOwnerAddress = connInfo.groupOwnerAddress?.hostAddress
                        if (groupOwnerAddress != null) {
                            fileMessaging.sendFile(
                                file = file,
                                hostAddress = groupOwnerAddress,
                                senderName = currentDevice.deviceName,
                                senderAddress = currentDevice.deviceAddress
                            )
                        } else {
                            Result.failure(Exception("Group owner address not available"))
                        }
                    }

                    if (result.isSuccess) {
                        successCount++
                    } else {
                        failCount++
                        Log.w("P2PSyncViewModel", "Failed to send file ${file.name}: ${result.exceptionOrNull()?.message}")
                    }
                }

                _folderTransferStatus.value = "Folder transfer completed: $successCount sent, $failCount failed"
                
            } catch (e: Exception) {
                _folderTransferStatus.value = "Error sending folder: ${e.message}"
                Log.e("P2PSyncViewModel", "Error sending folder", e)
            } finally {
                _isFolderTransferring.value = false
                _folderTransferProgress.value = emptyMap()
                // Reset to neutral mode after send attempt
                _folderTransferMode.value = "none"
                _selectedSendFolder.value = null
            }
        }
    }

    /**
     * Get all files in a URI-based folder
     */
    private fun getAllFilesInFolderUri(folderUri: Uri): List<File> {
        val fileList = mutableListOf<File>()
        try {
            val syncFileInfoList = fileMessaging.getFileListFromFolderUri(folderUri)
            
            // Convert SyncFileInfo to File objects by creating temporary files
            for (syncFileInfo in syncFileInfoList) {
                val tempFile = FolderUtils.getFileFromUriFolder(context, folderUri, syncFileInfo.relativePath)
                if (tempFile != null) {
                    fileList.add(tempFile)
                }
            }
        } catch (e: Exception) {
            Log.e("P2PSyncViewModel", "Error getting files from URI folder: ${e.message}", e)
        }
        return fileList
    }

    /**
     * Get all files in a folder recursively
     */
    private fun getAllFilesInFolder(folder: File): List<File> {
        return FolderUtils.getAllFilesInFolder(folder)
    }

    /**
     * Get count of files in a folder
     */
    private fun getFileCount(folder: File): Int {
        return getAllFilesInFolder(folder).size
    }

    /**
     * Check if folder can be sent
     */
    fun canSendFolder(): Boolean {
        return (_selectedSendFolder.value != null || _selectedSendFolderUri.value != null) && 
               _folderTransferMode.value == "send" && 
               canSendFiles()
    }

    /**
     * Clear folder transfer state
     */
    fun clearFolderTransfer() {
        _folderTransferMode.value = "none"
        _selectedSendFolder.value = null
        _selectedSendFolderUri.value = null
        _selectedReceiveFolder.value = null
        _selectedReceiveFolderUri.value = null
        _folderTransferStatus.value = ""
        _folderTransferProgress.value = emptyMap()
        _isSyncMode.value = false
        _syncStatus.value = ""
        _filesToSync.value = emptyList()
    }

    // Folder Synchronization Methods

    /**
     * Enable sync mode for one-way folder synchronization
     */
    fun enableSyncMode() {
        _isSyncMode.value = true
        _folderTransferStatus.value = "Sync mode enabled - Select folders on both devices"
    }

    /**
     * Disable sync mode
     */
    fun disableSyncMode() {
        _isSyncMode.value = false
        _syncStatus.value = ""
        _filesToSync.value = emptyList()
        fileMessaging.setSyncingState(false)
    }

    /**
     * Start folder comparison for synchronization
     */
    fun startFolderComparison() {
        val sendFolder = _selectedSendFolder.value
        val sendFolderUri = _selectedSendFolderUri.value
        
        if (sendFolder == null && sendFolderUri == null) {
            _syncStatus.value = "Please select a source folder to sync"
            return
        }

        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            _syncStatus.value = "No P2P connection available"
            return
        }

        val currentDevice = thisDevice.value
        if (currentDevice == null) {
            _syncStatus.value = "Device information not available"
            return
        }

        viewModelScope.launch {
            try {
                _syncStatus.value = "Analyzing source folder..."
                fileMessaging.setSyncingState(true)

                // Get source files from either File or URI
                val sourceFiles = if (sendFolder != null) {
                    fileMessaging.getFileListFromFolder(sendFolder)
                } else {
                    fileMessaging.getFileListFromFolderUri(sendFolderUri!!)
                }
                Log.d("P2PSyncViewModel", "Found ${sourceFiles.size} files in source folder")

                if (sourceFiles.isEmpty()) {
                    _syncStatus.value = "No files found in source folder"
                    fileMessaging.setSyncingState(false)
                    return@launch
                }

                _syncStatus.value = "Requesting file list from receiver..."

                // Get target files from remote device
                val targetAddress = if (connInfo.isGroupOwner) {
                    // Get first connected client
                    val clients = fileMessaging.getAllActiveClientIPs()
                    if (clients.isEmpty()) {
                        _syncStatus.value = "No connected clients found"
                        fileMessaging.setSyncingState(false)
                        return@launch
                    }
                    clients.first()
                } else {
                    // Send to group owner
                    connInfo.groupOwnerAddress?.hostAddress
                }

                if (targetAddress == null) {
                    _syncStatus.value = "Target device address not available"
                    fileMessaging.setSyncingState(false)
                    return@launch
                }

                val targetFilesResult = fileMessaging.requestFileListFromRemote(targetAddress)
                
                if (targetFilesResult.isFailure) {
                    _syncStatus.value = "Failed to get file list from receiver: ${targetFilesResult.exceptionOrNull()?.message}"
                    fileMessaging.setSyncingState(false)
                    return@launch
                }

                val targetFiles = targetFilesResult.getOrNull() ?: emptyList()
                Log.d("P2PSyncViewModel", "Received ${targetFiles.size} files from target device")

                _syncStatus.value = "Comparing files..."

                // Compare files and determine what needs to be synced
                val filesToSync = fileMessaging.getFilesToSync(sourceFiles, targetFiles)
                
                _filesToSync.value = filesToSync.map { "${it.relativePath} (${formatFileSize(it.size)})" }
                
                if (filesToSync.isEmpty()) {
                    _syncStatus.value = "All files are up to date - No sync needed"
                } else {
                    _syncStatus.value = "Ready to sync ${filesToSync.size} files"
                    Log.d("P2PSyncViewModel", "Files to sync: ${filesToSync.map { it.relativePath }}")
                }

                fileMessaging.setSyncingState(false)

            } catch (e: Exception) {
                _syncStatus.value = "Error during folder comparison: ${e.message}"
                fileMessaging.setSyncingState(false)
                Log.e("P2PSyncViewModel", "Error in folder comparison", e)
            }
        }
    }

    /**
     * Start synchronization of identified files
     */
    fun startSync() {
        val sendFolder = _selectedSendFolder.value
        val sendFolderUri = _selectedSendFolderUri.value
        
        if (sendFolder == null && sendFolderUri == null) {
            _syncStatus.value = "Please select a source folder"
            return
        }

        val filesToSyncList = _filesToSync.value
        if (filesToSyncList.isEmpty()) {
            _syncStatus.value = "No files to sync. Run comparison first."
            return
        }

        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            _syncStatus.value = "No P2P connection available"
            return
        }

        val currentDevice = thisDevice.value
        if (currentDevice == null) {
            _syncStatus.value = "Device information not available"
            return
        }

        viewModelScope.launch {
            try {
                fileMessaging.setSyncingState(true)
                _syncStatus.value = "Starting file synchronization..."

                // Re-get source files to ensure we have the latest file objects
                val sourceFiles = if (sendFolder != null) {
                    fileMessaging.getFileListFromFolder(sendFolder)
                } else {
                    fileMessaging.getFileListFromFolderUri(sendFolderUri!!)
                }
                val sourceFileMap = sourceFiles.associateBy { it.relativePath }

                // Get files that need to be synced (extract relative paths from display strings)
                val filesToSyncPaths = _filesToSync.value.map { displayString ->
                    displayString.substringBefore(" (") // Remove size info
                }

                var successCount = 0
                var failCount = 0
                val totalFiles = filesToSyncPaths.size

                for ((index, relativePath) in filesToSyncPaths.withIndex()) {
                    val progress = ((index + 1) * 100) / totalFiles
                    _syncProgress.value = mapOf("overall" to progress)
                    
                    val sourceFileInfo = sourceFileMap[relativePath]
                    if (sourceFileInfo == null) {
                        Log.w("P2PSyncViewModel", "Source file not found: $relativePath")
                        failCount++
                        continue
                    }

                    val actualFile = if (sendFolder != null) {
                        File(sendFolder, relativePath)
                    } else {
                        // Get file from URI-based folder
                        FolderUtils.getFileFromUriFolder(context, sendFolderUri!!, relativePath)
                    }
                    
                    if (actualFile?.exists() != true) {
                        Log.w("P2PSyncViewModel", "Actual file not found: $relativePath")
                        failCount++
                        continue
                    }

                    _syncStatus.value = "Syncing ${actualFile.name} (${index + 1}/$totalFiles)"

                    val result = if (connInfo.isGroupOwner) {
                        fileMessaging.sendFileToAllClients(
                            file = actualFile,
                            senderName = currentDevice.deviceName,
                            senderAddress = currentDevice.deviceAddress
                        )
                    } else {
                        val groupOwnerAddress = connInfo.groupOwnerAddress?.hostAddress
                        if (groupOwnerAddress != null) {
                            fileMessaging.sendFile(
                                file = actualFile,
                                hostAddress = groupOwnerAddress,
                                senderName = currentDevice.deviceName,
                                senderAddress = currentDevice.deviceAddress
                            )
                        } else {
                            Result.failure(Exception("Group owner address not available"))
                        }
                    }

                    if (result.isSuccess) {
                        successCount++
                        Log.d("P2PSyncViewModel", "Successfully synced: $relativePath")
                    } else {
                        failCount++
                        Log.w("P2PSyncViewModel", "Failed to sync: $relativePath - ${result.exceptionOrNull()?.message}")
                    }
                }

                _syncStatus.value = "Synchronization completed: $successCount synced, $failCount failed"
                _filesToSync.value = emptyList() // Clear the list after sync

            } catch (e: Exception) {
                _syncStatus.value = "Error during synchronization: ${e.message}"
                Log.e("P2PSyncViewModel", "Error in synchronization", e)
            } finally {
                fileMessaging.setSyncingState(false)
                _syncProgress.value = emptyMap()
            }
        }
    }

    /**
     * Format file size for display
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> "${size / 1024}KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)}MB"
            else -> "${size / (1024 * 1024 * 1024)}GB"
        }
    }
}
