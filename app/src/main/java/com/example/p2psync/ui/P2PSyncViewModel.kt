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
import com.example.p2psync.network.P2PFileMessaging
import com.example.p2psync.network.SyncFileInfo
import com.example.p2psync.utils.FolderUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * ViewModel for managing P2P sync operations
 */
class P2PSyncViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context = application.applicationContext
    private val connectionManager = P2PConnectionManager(context)
    private val fileMessaging = P2PFileMessaging(context)

    // Expose state flows from managers
    val isWifiP2pEnabled = connectionManager.isWifiP2pEnabled
    val discoveredDevices = connectionManager.discoveredDevices
    val connectionInfo = connectionManager.connectionInfo
    val thisDevice = connectionManager.thisDevice
    val isDiscovering = connectionManager.isDiscovering
    val connectionStatus = connectionManager.connectionStatus
    
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

    // Two-way sync state
    private val _selectedLocalFolder = MutableStateFlow<File?>(null)
    val selectedLocalFolder: StateFlow<File?> = _selectedLocalFolder.asStateFlow()

    private val _selectedLocalFolderUri = MutableStateFlow<Uri?>(null)
    val selectedLocalFolderUri: StateFlow<Uri?> = _selectedLocalFolderUri.asStateFlow()

    private val _twoWaySyncProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val twoWaySyncProgress: StateFlow<Map<String, Int>> = _twoWaySyncProgress.asStateFlow()

    private val _twoWaySyncStatus = MutableStateFlow("")
    val twoWaySyncStatus: StateFlow<String> = _twoWaySyncStatus.asStateFlow()

    private val _filesToSyncToRemote = MutableStateFlow<List<String>>(emptyList())
    val filesToSyncToRemote: StateFlow<List<String>> = _filesToSyncToRemote.asStateFlow()

    private val _filesToSyncToLocal = MutableStateFlow<List<String>>(emptyList())
    val filesToSyncToLocal: StateFlow<List<String>> = _filesToSyncToLocal.asStateFlow()

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
        Log.d("P2PSyncViewModel", "openFile: Attempting to open ${fileMessage.fileName}")
        Log.d("P2PSyncViewModel", "openFile: Transfer status: ${fileMessage.transferStatus}")
        Log.d("P2PSyncViewModel", "openFile: File path: ${fileMessage.filePath}")
        
        if (!fileMessage.canBeOpened()) {
            Log.w("P2PSyncViewModel", "openFile: File cannot be opened - status: ${fileMessage.transferStatus}")
            _statusMessage.value = "File is not available to open"
            return
        }

        viewModelScope.launch {
            try {
                // Get the actual openable file (handles dual save scenarios)
                val file = fileMessage.getOpenableFile(context.cacheDir)
                Log.d("P2PSyncViewModel", "openFile: Resolved file location: ${file?.absolutePath}")
                Log.d("P2PSyncViewModel", "openFile: File exists: ${file?.exists()}")
                Log.d("P2PSyncViewModel", "openFile: File readable: ${file?.canRead()}")
                
                if (file == null || !file.exists()) {
                    Log.e("P2PSyncViewModel", "openFile: File not found or doesn't exist")
                    _statusMessage.value = "File not found: ${fileMessage.fileName}"
                    return@launch
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "com.example.p2psync.fileprovider",
                        file
                    )
                    Log.d("P2PSyncViewModel", "openFile: Generated URI: $uri")
                    Log.d("P2PSyncViewModel", "openFile: MIME type: ${fileMessage.mimeType}")
                    setDataAndType(uri, fileMessage.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(intent)
                    Log.i("P2PSyncViewModel", "openFile: Successfully opened file with specific MIME type")
                    _statusMessage.value = "Opening ${fileMessage.fileName} from ${if (file.absolutePath == fileMessage.filePath) "target location" else "cache"}"
                } catch (e: Exception) {
                    Log.w("P2PSyncViewModel", "openFile: Failed to open with specific MIME type: ${e.message}")
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
                        Log.i("P2PSyncViewModel", "openFile: Successfully opened file with generic MIME type")
                        _statusMessage.value = "Opening ${fileMessage.fileName}"
                    } catch (e2: Exception) {
                        Log.e("P2PSyncViewModel", "openFile: Failed to open file with both MIME types: ${e2.message}")
                        _statusMessage.value = "No app available to open this file type"
                    }
                }
            } catch (e: Exception) {
                Log.e("P2PSyncViewModel", "openFile: General error opening file: ${e.message}")
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

    // Two-way sync operations
    fun setSelectedLocalFolder(folder: File?) {
        _selectedLocalFolder.value = folder
        _selectedLocalFolderUri.value = null
        // Also set in P2PFileMessaging for two-way sync
        Log.d("P2PSyncViewModel", "Setting selected local folder: ${folder?.absolutePath}")
        fileMessaging.setTwoWaySyncFolder(folder)
        fileMessaging.setTwoWaySyncFolderUri(null)
    }

    fun setSelectedLocalFolderUri(uri: Uri?, folderName: String?) {
        _selectedLocalFolderUri.value = uri
        _selectedLocalFolder.value = null
        // Also set in P2PFileMessaging for two-way sync
        Log.d("P2PSyncViewModel", "Setting selected local folder URI: $uri")
        fileMessaging.setTwoWaySyncFolder(null)
        fileMessaging.setTwoWaySyncFolderUri(uri)
    }

    fun startTwoWayComparison() {
        val folder = _selectedLocalFolder.value
        val folderUri = _selectedLocalFolderUri.value
        
        if (folder == null && folderUri == null) {
            _twoWaySyncStatus.value = "Please select a folder first"
            return
        }

        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            _twoWaySyncStatus.value = "No P2P connection available"
            return
        }

        _twoWaySyncStatus.value = "Analyzing local and remote folders..."
        
        viewModelScope.launch {
            try {
                // Get local folder files
                val localFiles = getLocalFolderFiles(folder, folderUri)
                Log.d("P2PSyncViewModel", "Local files found: ${localFiles.size}")
                
                // Get remote folder files by requesting file list from the connected device
                val remoteFiles = requestRemoteFolderFiles()
                Log.d("P2PSyncViewModel", "Remote files received: ${remoteFiles.size}")
                
                // Compare and determine what needs to be synced
                val (toRemote, toLocal) = compareForTwoWaySync(localFiles, remoteFiles)
                
                // Create display name to relative path mapping for sync operations
                displayToPathMapping = createDisplayToPathMapping(localFiles, remoteFiles)
                
                _filesToSyncToRemote.value = toRemote
                _filesToSyncToLocal.value = toLocal
                
                _twoWaySyncStatus.value = "Comparison complete: ${toRemote.size} files to send, ${toLocal.size} files to receive"
                
            } catch (e: Exception) {
                Log.e("P2PSyncViewModel", "Error during two-way comparison", e)
                _twoWaySyncStatus.value = "Error during comparison: ${e.message}"
            }
        }
    }

    private suspend fun getLocalFolderFiles(folder: File?, folderUri: Uri?): List<SyncFileInfo> {
        return withContext(Dispatchers.IO) {
            val files = mutableListOf<SyncFileInfo>()
            
            if (folder != null && folder.exists()) {
                // Handle File-based folder
                folder.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val relativePath = file.relativeTo(folder).path
                        val checksum = calculateFileChecksum(file) // Calculate checksum for each file
                        files.add(SyncFileInfo(
                            name = file.name,
                            size = file.length(),
                            relativePath = relativePath,
                            checksum = checksum
                        ))
                    }
                }
            } else if (folderUri != null && context != null) {
                // Handle DocumentFile-based folder
                val documentFile = DocumentFile.fromTreeUri(context, folderUri)
                documentFile?.let { doc ->
                    addDocumentFiles(doc, "", files)
                }
            }
            
            files
        }
    }

    private suspend fun addDocumentFiles(documentFile: DocumentFile, relativePath: String, files: MutableList<SyncFileInfo>) {
        documentFile.listFiles().forEach { file ->
            if (file.isFile) {
                val currentPath = if (relativePath.isEmpty()) file.name ?: "" else "$relativePath/${file.name}"
                val checksum = calculateDocumentFileChecksum(file) // Calculate checksum for DocumentFile
                files.add(SyncFileInfo(
                    name = file.name ?: "",
                    size = file.length(),
                    relativePath = currentPath,
                    checksum = checksum
                ))
            } else if (file.isDirectory) {
                val currentPath = if (relativePath.isEmpty()) file.name ?: "" else "$relativePath/${file.name}"
                addDocumentFiles(file, currentPath, files)
            }
        }
    }

    /**
     * Perform one-way sync for specified files using existing one-way sync functionality
     */
    private suspend fun performOneWaySync(filesToSync: List<String>, folder: File?, folderUri: Uri?, isSending: Boolean) {
        withContext(Dispatchers.IO) {
            val connInfo = connectionInfo.value
            val currentDevice = thisDevice.value
            
            if (connInfo?.groupFormed != true || currentDevice == null) {
                Log.w("P2PSyncViewModel", "Cannot perform one-way sync: No connection or device info")
                return@withContext
            }

            var successCount = 0
            var failCount = 0
            val totalFiles = filesToSync.size

            for ((index, displayName) in filesToSync.withIndex()) {
                val progress = ((index + 1) * 100) / totalFiles
                _twoWaySyncProgress.value = mapOf("overall" to progress)
                
                // Extract the actual relative path from the display name
                val relativePath = displayToPathMapping[displayName] ?: extractRelativePathFromDisplayName(displayName)
                
                _twoWaySyncStatus.value = if (isSending) {
                    "Sending ${File(relativePath).name} (${index + 1}/$totalFiles)"
                } else {
                    "Receiving ${File(relativePath).name} (${index + 1}/$totalFiles)"
                }

                try {
                    if (isSending) {
                        // Send file using existing file messaging
                        val result = if (folder != null) {
                            val file = File(folder, relativePath)
                            if (file.exists()) {
                                sendFileUsingOneWaySync(file, currentDevice, connInfo)
                            } else {
                                Result.failure(Exception("File not found: $relativePath"))
                            }
                        } else if (folderUri != null) {
                            // Handle DocumentFile-based sending
                            sendDocumentFileUsingOneWaySync(folderUri, relativePath, currentDevice, connInfo)
                        } else {
                            Result.failure(Exception("No valid folder selected"))
                        }
                        
                        if (result.isSuccess) {
                            successCount++
                            Log.d("P2PSyncViewModel", "Successfully sent: $relativePath")
                        } else {
                            failCount++
                            Log.w("P2PSyncViewModel", "Failed to send: $relativePath - ${result.exceptionOrNull()?.message}")
                        }
                    } else {
                        // For receiving, files should already be coming through the normal file messaging system
                        // Just mark as successful for progress tracking
                        successCount++
                    }
                } catch (e: Exception) {
                    failCount++
                    Log.e("P2PSyncViewModel", "Error processing file: $relativePath", e)
                }
            }

            Log.d("P2PSyncViewModel", "One-way sync phase completed: $successCount success, $failCount failed")
        }
    }

    /**
     * Send file using existing one-way sync functionality
     */
    private suspend fun sendFileUsingOneWaySync(file: File, currentDevice: P2PDevice, connInfo: android.net.wifi.p2p.WifiP2pInfo): Result<Unit> {
        return if (connInfo.isGroupOwner) {
            fileMessaging.sendFileToAllClients(
                file = file,
                senderName = currentDevice.deviceName,
                senderAddress = currentDevice.deviceAddress
            ).map { Unit }
        } else {
            val groupOwnerAddress = connInfo.groupOwnerAddress?.hostAddress
            if (groupOwnerAddress != null) {
                fileMessaging.sendFile(
                    file = file,
                    hostAddress = groupOwnerAddress,
                    senderName = currentDevice.deviceName,
                    senderAddress = currentDevice.deviceAddress
                ).map { Unit }
            } else {
                Result.failure(Exception("Group owner address not available"))
            }
        }
    }

    /**
     * Send DocumentFile using existing one-way sync functionality
     */
    private suspend fun sendDocumentFileUsingOneWaySync(folderUri: Uri, relativePath: String, currentDevice: P2PDevice, connInfo: android.net.wifi.p2p.WifiP2pInfo): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (context == null) return@withContext Result.failure(Exception("Context is null"))
                
                val documentFile = DocumentFile.fromTreeUri(context, folderUri)
                val targetFile = findDocumentFileByPath(documentFile, relativePath)
                
                if (targetFile != null) {
                    // Read file data
                    val fileData = context.contentResolver.openInputStream(targetFile.uri)?.use { input ->
                        input.readBytes()
                    } ?: return@withContext Result.failure(Exception("Failed to read file data"))
                    
                    // Get the original filename from the relative path
                    val originalFileName = File(relativePath).name
                    
                    // Create a temporary file with the original name (not prefixed)
                    val tempFile = File(context.cacheDir, originalFileName)
                    
                    // If file already exists, create unique name but keep original extension
                    val finalTempFile = if (tempFile.exists()) {
                        val nameWithoutExt = originalFileName.substringBeforeLast(".", originalFileName)
                        val extension = if (originalFileName.contains(".")) {
                            ".${originalFileName.substringAfterLast(".")}"
                        } else {
                            ""
                        }
                        File(context.cacheDir, "${nameWithoutExt}_${System.currentTimeMillis()}${extension}")
                    } else {
                        tempFile
                    }
                    
                    finalTempFile.writeBytes(fileData)
                    
                    val result = sendFileUsingOneWaySync(finalTempFile, currentDevice, connInfo)
                    
                    // Clean up temp file
                    finalTempFile.delete()
                    
                    result
                } else {
                    Result.failure(Exception("DocumentFile not found: $relativePath"))
                }
            } catch (e: Exception) {
                Log.e("P2PSyncViewModel", "Error sending DocumentFile: $relativePath", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Trigger remote device to send files to this device
     */
    private suspend fun triggerRemoteDeviceToSend(filesToReceive: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                Log.d("P2PSyncViewModel", "Triggering remote device to send ${filesToReceive.size} files")
                
                // Get connection info to find the remote device address
                val connInfo = connectionManager.connectionInfo.value
                if (connInfo != null) {
                    val targetAddress = if (connInfo.isGroupOwner) {
                        // If we're group owner, we need to send trigger to client
                        // For now, we'll use the first connected client
                        val clients = fileMessaging.getConnectedClients()
                        clients.firstOrNull()
                    } else {
                        // If we're client, send trigger to group owner
                        connInfo.groupOwnerAddress?.hostAddress
                    }
                    
                    if (targetAddress != null) {
                        fileMessaging.sendTriggerMessage(targetAddress)
                        Log.d("P2PSyncViewModel", "Trigger message sent to $targetAddress")
                    } else {
                        Log.w("P2PSyncViewModel", "No target address available for trigger")
                    }
                } else {
                    Log.w("P2PSyncViewModel", "No connection info available for trigger")
                }
            } catch (e: Exception) {
                Log.e("P2PSyncViewModel", "Error triggering remote device to send", e)
            }
        }
    }

    /**
     * Request remote folder files using existing one-way sync functionality 
     */
    private suspend fun requestRemoteFolderFiles(): List<SyncFileInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val connInfo = connectionInfo.value
                if (connInfo?.groupFormed != true) {
                    Log.w("P2PSyncViewModel", "No P2P connection available for requesting remote files")
                    return@withContext emptyList()
                }

                // Get target address for requesting file list
                val targetAddress = if (connInfo.isGroupOwner) {
                    // Get first connected client
                    val clients = fileMessaging.getAllActiveClientIPs()
                    if (clients.isEmpty()) {
                        Log.w("P2PSyncViewModel", "No connected clients found")
                        return@withContext emptyList()
                    }
                    clients.first()
                } else {
                    // Send to group owner
                    connInfo.groupOwnerAddress?.hostAddress
                }

                if (targetAddress == null) {
                    Log.w("P2PSyncViewModel", "Target device address not available")
                    return@withContext emptyList()
                }

                // Request file list from remote device's selected two-way sync folder
                val result = fileMessaging.requestTwoWaySyncFolderFiles(targetAddress)
                
                if (result.isSuccess) {
                    val remoteFiles = result.getOrNull() ?: emptyList()
                    Log.d("P2PSyncViewModel", "Successfully received ${remoteFiles.size} files from remote device's selected folder")
                    remoteFiles
                } else {
                    Log.e("P2PSyncViewModel", "Failed to get remote two-way sync folder list: ${result.exceptionOrNull()?.message}")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e("P2PSyncViewModel", "Error requesting remote folder files", e)
                emptyList()
            }
        }
    }

    private fun compareForTwoWaySync(localFiles: List<SyncFileInfo>, remoteFiles: List<SyncFileInfo>): Pair<List<String>, List<String>> {
        val localFileMap = localFiles.associateBy { it.relativePath }
        val remoteFileMap = remoteFiles.associateBy { it.relativePath }
        
        val filesToSendToRemote = mutableListOf<String>()
        val filesToReceiveFromRemote = mutableListOf<String>()
        
        // Find files that exist locally but not remotely
        localFiles.forEach { localFile ->
            if (!remoteFileMap.containsKey(localFile.relativePath)) {
                val displayName = formatFileDisplayName(localFile.name, localFile.relativePath, localFile.size)
                filesToSendToRemote.add(displayName)
                Log.d("P2PSyncViewModel", "File to send: ${localFile.relativePath} (missing on remote)")
            }
        }
        
        // Find files that exist remotely but not locally
        remoteFiles.forEach { remoteFile ->
            if (!localFileMap.containsKey(remoteFile.relativePath)) {
                val displayName = formatFileDisplayName(remoteFile.name, remoteFile.relativePath, remoteFile.size)
                filesToReceiveFromRemote.add(displayName)
                Log.d("P2PSyncViewModel", "File to receive: ${remoteFile.relativePath} (missing locally)")
            }
        }
        
        // For files that exist in both places, compare sizes and checksums
        localFiles.forEach { localFile ->
            val remoteFile = remoteFileMap[localFile.relativePath]
            if (remoteFile != null) {
                // Check if files are different based on size
                if (localFile.size != remoteFile.size) {
                    // Files have different sizes, determine which one to keep
                    // For now, keep the larger file (could be user preference)
                    if (remoteFile.size > localFile.size) {
                        val displayName = formatFileDisplayName(
                            remoteFile.name, 
                            remoteFile.relativePath, 
                            remoteFile.size, 
                            "Larger version (${formatFileSize(remoteFile.size)} vs ${formatFileSize(localFile.size)})"
                        )
                        filesToReceiveFromRemote.add(displayName)
                        Log.d("P2PSyncViewModel", "File to receive: ${remoteFile.relativePath} (remote larger: ${remoteFile.size} > ${localFile.size})")
                    } else if (localFile.size > remoteFile.size) {
                        val displayName = formatFileDisplayName(
                            localFile.name, 
                            localFile.relativePath, 
                            localFile.size, 
                            "Larger version (${formatFileSize(localFile.size)} vs ${formatFileSize(remoteFile.size)})"
                        )
                        filesToSendToRemote.add(displayName)
                        Log.d("P2PSyncViewModel", "File to send: ${localFile.relativePath} (local larger: ${localFile.size} > ${remoteFile.size})")
                    }
                }
                // If sizes are the same, check checksums if available
                else if (localFile.checksum != null && remoteFile.checksum != null) {
                    if (localFile.checksum != remoteFile.checksum) {
                        // Files have same size but different content
                        // In this case, we could ask user or use other criteria
                        // For now, prefer local file (could be made configurable)
                        val displayName = formatFileDisplayName(
                            localFile.name, 
                            localFile.relativePath, 
                            localFile.size, 
                            "Different content (same size)"
                        )
                        filesToSendToRemote.add(displayName)
                        Log.d("P2PSyncViewModel", "File to send: ${localFile.relativePath} (different checksum but same size)")
                    }
                    // If checksums match, files are identical - no action needed
                    else {
                        Log.d("P2PSyncViewModel", "File identical: ${localFile.relativePath} (same size and checksum)")
                    }
                }
                // If sizes are same but no checksums available, assume files are identical
                else {
                    Log.d("P2PSyncViewModel", "File assumed identical: ${localFile.relativePath} (same size, no checksums)")
                }
            }
        }
        
        Log.d("P2PSyncViewModel", "Comparison complete: ${filesToSendToRemote.size} to send, ${filesToReceiveFromRemote.size} to receive")
        return Pair(filesToSendToRemote, filesToReceiveFromRemote)
    }
    
    /**
     * Format file name for display in the UI
     */
    private fun formatFileDisplayName(fileName: String, relativePath: String, fileSize: Long, reason: String? = null): String {
        val formattedSize = formatFileSize(fileSize)
        val displayPath = if (relativePath.contains("/")) {
            "📁 ${relativePath.substringBeforeLast("/")}/"
        } else {
            ""
        }
        
        return buildString {
            append("📄 $fileName")
            if (displayPath.isNotEmpty()) {
                append("\n   $displayPath")
            }
            append(" (${formattedSize})")
            if (reason != null) {
                append("\n   ℹ️ $reason")
            }
        }
    }

    /**
     * Extract relative path from formatted display name
     */
    private fun extractRelativePathFromDisplayName(displayName: String): String {
        // The relative path is embedded in the display name
        // We need to reconstruct it from the file name and folder path
        val lines = displayName.split("\n")
        val fileNameLine = lines.first() // "📄 filename.ext (size)"
        val fileName = fileNameLine.removePrefix("📄 ").substringBefore(" (")
        
        // Check if there's a folder path
        val folderLine = lines.find { it.trim().startsWith("📁 ") }
        return if (folderLine != null) {
            val folderPath = folderLine.trim().removePrefix("📁 ").removeSuffix("/")
            "$folderPath/$fileName"
        } else {
            fileName
        }
    }
    
    /**
     * Create mapping from display names to relative paths
     */
    private fun createDisplayToPathMapping(localFiles: List<SyncFileInfo>, remoteFiles: List<SyncFileInfo>): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        
        // Map local files
        localFiles.forEach { file ->
            val displayName = formatFileDisplayName(file.name, file.relativePath, file.size)
            mapping[displayName] = file.relativePath
        }
        
        // Map remote files
        remoteFiles.forEach { file ->
            val displayName = formatFileDisplayName(file.name, file.relativePath, file.size)
            mapping[displayName] = file.relativePath
        }
        
        return mapping
    }

    // Store the mapping for use in sync operations
    private var displayToPathMapping: Map<String, String> = emptyMap()

    /**
     * Start two-way sync between Device A and Device B
     * Device A sends selected files to Device B, then Device B sends its files back to Device A
     */
    fun startTwoWaySync() {
        val folder = _selectedLocalFolder.value
        val folderUri = _selectedLocalFolderUri.value
        
        if (folder == null && folderUri == null) {
            _twoWaySyncStatus.value = "Please select a folder first"
            return
        }

        val connInfo = connectionInfo.value
        if (connInfo?.groupFormed != true) {
            _twoWaySyncStatus.value = "No P2P connection available"
            return
        }

        val filesToSend = _filesToSyncToRemote.value
        val filesToReceive = _filesToSyncToLocal.value
        
        if (filesToSend.isEmpty() && filesToReceive.isEmpty()) {
            _twoWaySyncStatus.value = "No files to sync. Run comparison first."
            return
        }

        _twoWaySyncStatus.value = "Starting sequential two-way sync..."
        _twoWaySyncProgress.value = emptyMap()
        
        viewModelScope.launch {
            try {
                // Ensure the two-way sync folder is set in P2PFileMessaging
                if (folder != null) {
                    Log.d("P2PSyncViewModel", "Setting two-way sync File folder: ${folder.absolutePath}")
                    fileMessaging.setTwoWaySyncFolder(folder)
                    fileMessaging.setTwoWaySyncFolderUri(null)
                } else if (folderUri != null) {
                    Log.d("P2PSyncViewModel", "Setting two-way sync URI folder: $folderUri")
                    fileMessaging.setTwoWaySyncFolder(null)
                    fileMessaging.setTwoWaySyncFolderUri(folderUri)
                }
                
                Log.d("P2PSyncViewModel", "Starting two-way sync - filesToSend: ${filesToSend.size}, filesToReceive: ${filesToReceive.size}")
                Log.d("P2PSyncViewModel", "Two-way sync folder set: ${if (folder != null) folder.absolutePath else folderUri.toString()}")
                
                // Phase 1: Send files from Device A to Device B using one-way sync
                if (filesToSend.isNotEmpty()) {
                    _twoWaySyncStatus.value = "Phase 1: Sending ${filesToSend.size} files to Device B..."
                    Log.d("P2PSyncViewModel", "Phase 1: Sending files to remote using one-way sync")
                    
                    // Use one-way sync to send files
                    performOneWaySync(filesToSend, folder, folderUri, true) // true for sending
                    
                    // Wait for completion
                    delay(2000)
                }
                
                // Phase 2: Trigger Device B to send files to Device A using one-way sync
                if (filesToReceive.isNotEmpty()) {
                    _twoWaySyncStatus.value = "Phase 2: Requesting Device B to send ${filesToReceive.size} files..."
                    Log.d("P2PSyncViewModel", "Phase 2: Triggering Device B to send files")
                    
                    // Send signal to Device B to start sending its files to Device A
                    triggerRemoteDeviceToSend(filesToReceive)
                    
                    // Wait for files to be received
                    delay(5000)
                }
                
                _twoWaySyncStatus.value = "Two-way sync completed successfully!"
                Log.d("P2PSyncViewModel", "Two-way sync completed")
                
                // Clear the sync lists
                _filesToSyncToRemote.value = emptyList()
                _filesToSyncToLocal.value = emptyList()
                
            } catch (e: Exception) {
                Log.e("P2PSyncViewModel", "Error during two-way sync", e)
                _twoWaySyncStatus.value = "Error during sync: ${e.message}"
            }
        }
    }

    private fun findDocumentFileByPath(documentFile: DocumentFile?, relativePath: String): DocumentFile? {
        if (documentFile == null) return null
        
        val pathParts = relativePath.split("/")
        var currentDoc = documentFile
        
        for (part in pathParts) {
            val found = currentDoc?.listFiles()?.find { 
                (it.isFile && it.name == part) || (it.isDirectory && it.name == part)
            }
            if (found == null) return null
            currentDoc = found
        }
        
        return if (currentDoc?.isFile == true) currentDoc else null
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

    /**
     * Calculate MD5 checksum for a file
     */
    private suspend fun calculateFileChecksum(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { inputStream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (inputStream.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w("P2PSyncViewModel", "Failed to calculate checksum for ${file.absolutePath}: ${e.message}")
            null
        }
    }

    /**
     * Calculate checksum for DocumentFile (more complex, may skip for performance)
     */
    private suspend fun calculateDocumentFileChecksum(documentFile: DocumentFile): String? = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>().applicationContext
            val digest = MessageDigest.getInstance("MD5")
            context.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (inputStream.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w("P2PSyncViewModel", "Failed to calculate checksum for DocumentFile: ${e.message}")
            null
        }
    }
}
