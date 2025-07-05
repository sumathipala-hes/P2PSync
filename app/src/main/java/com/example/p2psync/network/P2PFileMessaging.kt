package com.example.p2psync.network

import android.util.Log
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.p2psync.data.FileMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Data class to hold client connection information
 */
data class ClientInfo(
    val ipAddress: String,
    val port: Int,
    val connectionTime: Long = System.currentTimeMillis(),
    val lastActivity: Long = System.currentTimeMillis(),
    var isActive: Boolean = true
)

/**
 * Service for sending and receiving files over P2P connection with messaging interface
 */
class P2PFileMessaging(private val context: android.content.Context? = null) {

    companion object {
        private const val TAG = "P2PFileMessaging"
        private const val FILE_PORT = 8890
        private const val CONNECTION_TIMEOUT = 10000
        private const val BUFFER_SIZE = 8192
        private const val METADATA_SEPARATOR = ":::"
        private const val KEEP_ALIVE_MESSAGE = "KEEP_ALIVE"
        private const val CLIENT_HELLO_MESSAGE = "CLIENT_HELLO"
        private const val KEEP_ALIVE_INTERVAL = 30000L
    }

    private val _fileMessages = MutableStateFlow<List<FileMessage>>(emptyList())
    val fileMessages: StateFlow<List<FileMessage>> = _fileMessages.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Not Connected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _transferProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val transferProgress: StateFlow<Map<String, Int>> = _transferProgress.asStateFlow()

    private val _connectedClientsInfo = MutableStateFlow<List<String>>(emptyList())
    val connectedClientsInfo: StateFlow<List<String>> = _connectedClientsInfo.asStateFlow()

    private var messageServerSocket: ServerSocket? = null
    private var isServerRunning = false
    private var serverJob: Job? = null
    
    private val connectedClients = ConcurrentHashMap<String, ClientInfo>() // clientAddress -> ClientInfo
    private val activeConnections = ConcurrentHashMap<String, Socket>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Receive directory state
    private var customReceiveDirectory: File? = null
    private var customReceiveDirectoryUri: Uri? = null

    /**
     * Start listening for incoming file transfers (server mode)
     */
    suspend fun startFileServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isServerRunning) {
                Log.d(TAG, "File server already running")
                return@withContext Result.success(Unit)
            }

            messageServerSocket = ServerSocket(FILE_PORT)
            isServerRunning = true
            _isListening.value = true
            _connectionStatus.value = "Listening for files..."

            Log.d(TAG, "File server started on port $FILE_PORT")

            serverJob = coroutineScope.launch {
                try {
                    while (isServerRunning && messageServerSocket?.isClosed == false) {
                        try {
                            val clientSocket = messageServerSocket?.accept()
                            clientSocket?.let { socket ->
                                val clientAddress = socket.remoteSocketAddress.toString()
                                Log.d(TAG, "New file client connected: $clientAddress")
                                
                                activeConnections[clientAddress] = socket
                                addConnectedClient(clientAddress)
                                
                                val connectionJob = launch {
                                    handleFileConnection(socket, clientAddress)
                                }
                                connectionJobs[clientAddress] = connectionJob
                            }
                        } catch (e: Exception) {
                            if (isServerRunning) {
                                Log.e(TAG, "Error accepting file connection: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isServerRunning) {
                        Log.e(TAG, "Server loop error: ${e.message}")
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting file server: ${e.message}", e)
            _isListening.value = false
            _connectionStatus.value = "Error starting server"
            Result.failure(e)
        }
    }

    /**
     * Stop the file server
     */
    fun stopFileServer() {
        try {
            isServerRunning = false
            
            connectionJobs.values.forEach { it.cancel() }
            connectionJobs.clear()
            
            activeConnections.values.forEach { socket ->
                try {
                    socket.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing connection: ${e.message}")
                }
            }
            activeConnections.clear()
            
            serverJob?.cancel()
            
            messageServerSocket?.close()
            messageServerSocket = null
            
            _isListening.value = false
            _connectionStatus.value = "Server stopped"
            Log.d(TAG, "File server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping file server: ${e.message}")
        }
    }

    /**
     * Send a file to the connected peer
     */
    suspend fun sendFile(
        file: File,
        hostAddress: String,
        senderName: String,
        senderAddress: String
    ): Result<FileMessage> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting to send file: ${file.name} to $hostAddress")
            Log.d(TAG, "File exists: ${file.exists()}, File size: ${file.length()}")
            
            val fileMessage = FileMessage(
                fileName = file.name,
                filePath = file.absolutePath,
                fileSize = file.length(),
                mimeType = getMimeType(file.name),
                senderName = senderName,
                senderAddress = senderAddress,
                isOutgoing = true,
                transferStatus = FileMessage.TransferStatus.TRANSFERRING
            )

            // Add to messages list immediately
            addFileMessage(fileMessage)
            updateTransferProgress(fileMessage.id, 0)

            val success = sendFileToHost(hostAddress, file, fileMessage)

            if (success) {
                updateFileMessageStatus(fileMessage.id, FileMessage.TransferStatus.COMPLETED, 100)
                Log.d(TAG, "File sent successfully: ${file.name}")
                Result.success(fileMessage)
            } else {
                updateFileMessageStatus(fileMessage.id, FileMessage.TransferStatus.FAILED, 0)
                throw Exception("Failed to send file")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending file: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Handle incoming file connection
     */
    private suspend fun handleFileConnection(socket: Socket, clientAddress: String) = withContext(Dispatchers.IO) {
        try {
            // Add client to connected clients list
            addConnectedClient(clientAddress)
            
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()
            
            while (socket.isConnected && !socket.isClosed) {
                try {
                    // Read metadata length first (4 bytes)
                    val lengthBuffer = ByteArray(4)
                    val lengthBytesRead = inputStream.read(lengthBuffer)
                    if (lengthBytesRead != 4) {
                        Log.w(TAG, "Could not read metadata length, connection may be closed")
                        break
                    }
                    
                    val metadataLength = java.nio.ByteBuffer.wrap(lengthBuffer).int
                    if (metadataLength <= 0 || metadataLength > 1024) { // Sanity check
                        Log.w(TAG, "Invalid metadata length: $metadataLength")
                        break
                    }
                    
                    // Read metadata
                    val metadataBuffer = ByteArray(metadataLength)
                    var totalRead = 0
                    while (totalRead < metadataLength) {
                        val bytesRead = inputStream.read(metadataBuffer, totalRead, metadataLength - totalRead)
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                    }
                    
                    if (totalRead != metadataLength) {
                        Log.w(TAG, "Could not read complete metadata")
                        break
                    }
                    
                    val metadataString = String(metadataBuffer, Charsets.UTF_8)
                    Log.d(TAG, "Received metadata: $metadataString")
                    
                    if (metadataString == KEEP_ALIVE_MESSAGE) {
                        continue
                    }

                    if (metadataString == CLIENT_HELLO_MESSAGE) {
                        Log.d(TAG, "Received CLIENT_HELLO from $clientAddress")
                        // Client is announcing its presence - no need to do anything special
                        // The client is already added to connectedClients when socket was accepted
                        // Send acknowledgment back
                        try {
                            outputStream.write("HELLO_ACK\n".toByteArray())
                            outputStream.flush()
                            Log.d(TAG, "Sent HELLO_ACK to $clientAddress")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send HELLO_ACK: ${e.message}")
                        }
                        continue
                    }

                    val metadata = metadataString.split(METADATA_SEPARATOR)
                    if (metadata.size >= 5) {
                        val fileName = metadata[0]
                        val fileSize = metadata[1].toLong()
                        val mimeType = metadata[2]
                        val senderName = metadata[3]
                        val senderAddress = metadata[4]

                        // Create file message
                        val fileMessage = FileMessage(
                            fileName = fileName,
                            filePath = "", // Will be set after receiving
                            fileSize = fileSize,
                            mimeType = mimeType,
                            senderName = senderName,
                            senderAddress = senderAddress,
                            isOutgoing = false,
                            transferStatus = FileMessage.TransferStatus.TRANSFERRING
                        )

                        addFileMessage(fileMessage)
                        updateTransferProgress(fileMessage.id, 0)

                        // Receive the file
                        val receivedFile = receiveFileData(inputStream, fileName, fileSize, fileMessage.id)
                        
                        if (receivedFile != null) {
                            val displayPath = getDisplayPath(receivedFile.absolutePath)
                            updateFileMessagePath(fileMessage.id, receivedFile.absolutePath, displayPath)
                            updateFileMessageStatus(fileMessage.id, FileMessage.TransferStatus.COMPLETED, 100)
                            
                            // Send acknowledgment
                            outputStream.write("ACK\n".toByteArray())
                            outputStream.flush()
                            Log.d(TAG, "File received successfully: $fileName at ${displayPath}")
                        } else {
                            updateFileMessageStatus(fileMessage.id, FileMessage.TransferStatus.FAILED, 0)
                            outputStream.write("NACK\n".toByteArray())
                            outputStream.flush()
                            Log.e(TAG, "Failed to receive file: $fileName")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling file transfer: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in file connection handler: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket: ${e.message}")
            }
            activeConnections.remove(clientAddress)
            connectionJobs.remove(clientAddress)
            
            // Don't remove client immediately - keep them for potential reconnection
            Log.d(TAG, "Client socket closed but keeping in connected list: $clientAddress")
        }
    }

    /**
     * Send file to host
     */
    private suspend fun sendFileToHost(hostAddress: String, file: File, fileMessage: FileMessage): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to $hostAddress:$FILE_PORT")
            
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, FILE_PORT), CONNECTION_TIMEOUT)
                Log.d(TAG, "Connected successfully to $hostAddress:$FILE_PORT")
                
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                
                // Prepare metadata
                val metadata = "${file.name}$METADATA_SEPARATOR${file.length()}$METADATA_SEPARATOR${getMimeType(file.name)}$METADATA_SEPARATOR${fileMessage.senderName}$METADATA_SEPARATOR${fileMessage.senderAddress}"
                val metadataBytes = metadata.toByteArray(Charsets.UTF_8)
                
                // Send metadata length first (4 bytes)
                val lengthBuffer = java.nio.ByteBuffer.allocate(4).putInt(metadataBytes.size).array()
                outputStream.write(lengthBuffer)
                
                // Send metadata
                outputStream.write(metadataBytes)
                outputStream.flush()
                
                Log.d(TAG, "Sent metadata: $metadata")

                // Send file data
                FileInputStream(file).use { fileInputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesTransferred = 0L
                    var bytesRead: Int

                    while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesTransferred += bytesRead

                        val progress = ((bytesTransferred * 100) / file.length()).toInt()
                        updateTransferProgress(fileMessage.id, progress)
                        
                        Log.v(TAG, "Sent $bytesTransferred/${file.length()} bytes ($progress%)")
                    }
                    outputStream.flush()
                }

                Log.d(TAG, "File data sent, waiting for acknowledgment...")

                // Wait for acknowledgment
                val reader = BufferedReader(InputStreamReader(inputStream))
                val ack = reader.readLine()
                
                Log.d(TAG, "Received acknowledgment: $ack")
                
                return@withContext ack == "ACK"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending file to host: ${e.message}", e)
            false
        }
    }

    /**
     * Receive file data
     */
    private suspend fun receiveFileData(inputStream: InputStream, fileName: String, fileSize: Long, messageId: String): File? = withContext(Dispatchers.IO) {
        try {
            // Check if we have a custom URI for receiving (folder sharing)
            val customUri = getCurrentReceiveDirectoryUri()
            
            if (customUri != null && context != null) {
                // Use Storage Access Framework for custom folder
                return@withContext receiveFileToCustomUri(inputStream, fileName, fileSize, messageId, customUri)
            } else {
                // Use traditional file system approach
                val receiveDir = getCurrentReceiveDirectory()
                val receivedFile = File(receiveDir, fileName)
                
                Log.d(TAG, "Starting to receive file: $fileName, size: $fileSize bytes, to: ${receivedFile.absolutePath}")
                
                FileOutputStream(receivedFile).use { fileOutputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesReceived = 0L
                    var bytesRead: Int

                    while (bytesReceived < fileSize) {
                        val remainingBytes = (fileSize - bytesReceived).toInt()
                        val bytesToRead = minOf(buffer.size, remainingBytes)
                        
                        bytesRead = inputStream.read(buffer, 0, bytesToRead)
                        if (bytesRead == -1) {
                            Log.w(TAG, "Unexpected end of stream at $bytesReceived/$fileSize bytes")
                            break
                        }
                        
                        fileOutputStream.write(buffer, 0, bytesRead)
                        bytesReceived += bytesRead

                        val progress = ((bytesReceived * 100) / fileSize).toInt()
                        updateTransferProgress(messageId, progress)
                        
                        if (bytesReceived % (BUFFER_SIZE * 10) == 0L || bytesReceived == fileSize) {
                            Log.v(TAG, "Received $bytesReceived/$fileSize bytes ($progress%)")
                        }
                    }
                }

                if (receivedFile.length() == fileSize) {
                    Log.d(TAG, "File received successfully: $fileName at ${receivedFile.absolutePath}")
                    receivedFile
                } else {
                    Log.e(TAG, "File size mismatch. Expected: $fileSize, Actual: ${receivedFile.length()}")
                    receivedFile.delete()
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving file data: ${e.message}", e)
            null
        }
    }

    /**
     * Receive file to custom URI using Storage Access Framework
     */
    private suspend fun receiveFileToCustomUri(
        inputStream: InputStream, 
        fileName: String, 
        fileSize: Long, 
        messageId: String, 
        folderUri: Uri
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (context == null) {
                Log.e(TAG, "Context is null, cannot write to custom URI")
                return@withContext null
            }

            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            if (documentFile == null || !documentFile.canWrite()) {
                Log.e(TAG, "Cannot write to folder URI: $folderUri")
                return@withContext null
            }

            // Create the file in the selected folder
            val newFile = documentFile.createFile("application/octet-stream", fileName)
            if (newFile == null) {
                Log.e(TAG, "Failed to create file in selected folder: $fileName")
                return@withContext null
            }

            Log.d(TAG, "Receiving file to custom folder: $fileName, size: $fileSize bytes")
            
            // Write the file content using SAF
            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesReceived = 0L
                var bytesRead: Int

                while (bytesReceived < fileSize) {
                    val remainingBytes = (fileSize - bytesReceived).toInt()
                    val bytesToRead = minOf(buffer.size, remainingBytes)
                    
                    bytesRead = inputStream.read(buffer, 0, bytesToRead)
                    if (bytesRead == -1) {
                        Log.w(TAG, "Unexpected end of stream at $bytesReceived/$fileSize bytes")
                        break
                    }
                    
                    outputStream.write(buffer, 0, bytesRead)
                    bytesReceived += bytesRead

                    val progress = ((bytesReceived * 100) / fileSize).toInt()
                    updateTransferProgress(messageId, progress)
                    
                    if (bytesReceived % (BUFFER_SIZE * 10) == 0L || bytesReceived == fileSize) {
                        Log.v(TAG, "Received $bytesReceived/$fileSize bytes ($progress%)")
                    }
                }
            }

            // Create a File object for display purposes (path won't be accessible but needed for FileMessage)
            val displayFile = File(getDisplayPathFromUri(newFile.uri), fileName)
            
            Log.d(TAG, "File received successfully to custom folder: $fileName")
            return@withContext displayFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving file to custom URI: ${e.message}", e)
            null
        }
    }

    /**
     * Get display path from URI for user-friendly display
     */
    private fun getDisplayPathFromUri(uri: Uri): String {
        return try {
            val documentFile = DocumentFile.fromSingleUri(context ?: return "Custom Folder", uri)
            val folderName = documentFile?.name ?: "Selected Folder"
            "Custom Folder/$folderName"
        } catch (e: Exception) {
            Log.w(TAG, "Error getting display path from URI: ${e.message}")
            "Custom Folder"
        }
    }

    /**
     * Get MIME type from file name
     */
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /**
     * Add file message to the list
     */
    private fun addFileMessage(fileMessage: FileMessage) {
        val currentMessages = _fileMessages.value.toMutableList()
        currentMessages.add(fileMessage)
        _fileMessages.value = currentMessages
    }

    /**
     * Update file message status
     */
    private fun updateFileMessageStatus(messageId: String, status: FileMessage.TransferStatus, progress: Int) {
        val currentMessages = _fileMessages.value.toMutableList()
        val index = currentMessages.indexOfFirst { it.id == messageId }
        if (index != -1) {
            currentMessages[index] = currentMessages[index].copy(
                transferStatus = status,
                progress = progress
            )
            _fileMessages.value = currentMessages
        }
    }

    /**
     * Update file message path and display path
     */
    private fun updateFileMessagePath(messageId: String, filePath: String, displayPath: String = "") {
        val currentMessages = _fileMessages.value.toMutableList()
        val index = currentMessages.indexOfFirst { it.id == messageId }
        if (index != -1) {
            currentMessages[index] = currentMessages[index].copy(
                filePath = filePath,
                displayPath = displayPath.ifEmpty { getDisplayPath(filePath) }
            )
            _fileMessages.value = currentMessages
        }
    }

    /**
     * Update transfer progress
     */
    private fun updateTransferProgress(messageId: String, progress: Int) {
        val currentProgress = _transferProgress.value.toMutableMap()
        currentProgress[messageId] = progress
        _transferProgress.value = currentProgress
    }

    /**
     * Clear all file messages
     */
    fun clearFileMessages() {
        _fileMessages.value = emptyList()
        _transferProgress.value = emptyMap()
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopFileServer()
        coroutineScope.cancel()
    }

    /**
     * Set custom directory for receiving files (for folder sharing)
     */
    fun setCustomReceiveDirectory(directory: File?) {
        customReceiveDirectory = directory
        Log.d(TAG, "Custom receive directory set to: ${directory?.absolutePath}")
    }

    /**
     * Set custom URI for receiving files (for folder sharing with SAF)
     */
    fun setCustomReceiveDirectoryUri(uri: Uri?) {
        customReceiveDirectoryUri = uri
        Log.d(TAG, "Custom receive directory URI set to: $uri")
    }

    /**
     * Get the current receive directory (custom or default)
     */
    fun getCurrentReceiveDirectory(): File {
        return customReceiveDirectory ?: getReceiveDirectory()
    }

    /**
     * Get the current receive directory URI (custom or null)
     */
    fun getCurrentReceiveDirectoryUri(): Uri? {
        return customReceiveDirectoryUri
    }

    /**
     * Get appropriate directory for storing received files
     */
    private fun getReceiveDirectory(): File {
        // Try different locations in order of preference
        val directories = mutableListOf<File>()
        
        // Add context-based directories if context is available
        context?.let { ctx ->
            // Try to use public Downloads directory first
            val publicDownloads = File("/storage/emulated/0/Download/P2PSync")
            directories.add(publicDownloads)
            
            // App-specific external files directory (fallback)
            ctx.getExternalFilesDir("Downloads")?.let { directories.add(it) }
            // App cache directory (last resort)
            directories.add(File(ctx.cacheDir, "received_files"))
        }
        
        // Fallback directories if context is not available
        directories.addAll(listOf(
            // Public Downloads with app folder
            File("/storage/emulated/0/Download/P2PSync"),
            // App-specific external directory
            File("/storage/emulated/0/Android/data/com.example.p2psync/files/Downloads"),
            // App cache directory
            File("/data/data/com.example.p2psync/cache/received_files")
        ))
        
        for (dir in directories) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                if (dir.exists() && dir.canWrite()) {
                    Log.d(TAG, "Using directory: ${dir.absolutePath}")
                    return dir
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot use directory: ${dir.absolutePath}, ${e.message}")
            }
        }
        
        // Final fallback
        Log.w(TAG, "Using fallback cache directory")
        return File("/data/data/com.example.p2psync/cache")
    }

    
    private fun getDisplayPath(filePath: String): String {
        return when {
            filePath.contains("/storage/emulated/0/Download/P2PSync") -> 
                "Downloads/P2PSync/"
            filePath.contains("/storage/emulated/0/Download") -> 
                "Downloads/"
            filePath.contains("Android/data/com.example.p2psync/files") -> 
                "App Files/"
            filePath.contains("cache") -> 
                "App Cache/"
            else -> {
                // Extract last two directories for display
                val file = File(filePath)
                val parent = file.parentFile
                val grandParent = parent?.parentFile
                when {
                    grandParent != null -> "${grandParent.name}/${parent.name}/"
                    parent != null -> "${parent.name}/"
                    else -> "Internal Storage/"
                }
            }
        }
    }

    /**
     * Add connected client with IP extraction
     */
    private fun addConnectedClient(clientAddress: String) {
        try {
            Log.d(TAG, "=== Adding Connected Client ===")
            Log.d(TAG, "Raw client address: $clientAddress")
            
            // Extract IP from socket address format: /192.168.1.100:12345
            val ipAddress = clientAddress.substringAfter("/").substringBefore(":")
            val port = clientAddress.substringAfterLast(":").toIntOrNull() ?: 0
            
            Log.d(TAG, "Extracted IP: $ipAddress")
            Log.d(TAG, "Extracted Port: $port")
            
            val clientInfo = ClientInfo(
                ipAddress = ipAddress,
                port = port,
                connectionTime = System.currentTimeMillis(),
                lastActivity = System.currentTimeMillis(),
                isActive = true
            )
            
            connectedClients[clientAddress] = clientInfo
            Log.d(TAG, "Total connected clients: ${connectedClients.size}")
            Log.d(TAG, "Connected clients map: $connectedClients")
            
            updateConnectedClientsStateFlow()
            Log.d(TAG, "Added client: $clientAddress with IP: $ipAddress")
            Log.d(TAG, "=== End Adding Client ===")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse client address: $clientAddress", e)
        }
    }

    /**
     * Remove connected client
     */
    private fun removeConnectedClient(clientAddress: String) {
        connectedClients.remove(clientAddress)
        updateConnectedClientsStateFlow()
        Log.d(TAG, "Removed client: $clientAddress")
    }

    /**
     * Update the connected clients StateFlow
     */
    private fun updateConnectedClientsStateFlow() {
        try {
            Log.d(TAG, "=== Updating Connected Clients StateFlow ===")
            Log.d(TAG, "Total clients in map: ${connectedClients.size}")
            
            val activeClients = connectedClients.values.filter { it.isActive }
            Log.d(TAG, "Active clients: ${activeClients.size}")
            
            val clientIPs = activeClients.map { it.ipAddress }.distinct()
            Log.d(TAG, "Client IPs: $clientIPs")
            
            _connectedClientsInfo.value = clientIPs
            Log.d(TAG, "StateFlow updated with: ${_connectedClientsInfo.value}")
            Log.d(TAG, "=== End StateFlow Update ===")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating connected clients StateFlow", e)
        }
    }

    /**
     * Get list of all active client IP addresses (for group owner broadcasting)
     */
    fun getAllActiveClientIPs(): List<String> {
        return connectedClients.values
            .filter { it.isActive }
            .map { it.ipAddress }
            .distinct()
            .also { ips ->
                Log.d(TAG, "Active client IPs: $ips")
            }
    }

    /**
     * Get list of connected clients (for compatibility)
     */
    fun getConnectedClients(): List<String> {
        return connectedClients.keys.toList()
    }

    /**
     * Send file to all connected clients (group owner broadcast)
     */
    suspend fun sendFileToAllClients(
        file: File,
        senderName: String,
        senderAddress: String
    ): Result<List<FileMessage>> = withContext(Dispatchers.IO) {
        try {
            val activeClientIPs = getAllActiveClientIPs()
            
            if (activeClientIPs.isEmpty()) {
                Log.w(TAG, "No active clients to send file to")
                return@withContext Result.failure(Exception("No clients connected"))
            }

            Log.d(TAG, "Broadcasting file ${file.name} to ${activeClientIPs.size} clients: $activeClientIPs")

            val results = mutableListOf<FileMessage>()
            val errors = mutableListOf<Exception>()

            // Send to each client sequentially to avoid overwhelming the connection
            for (clientIP in activeClientIPs) {
                try {
                    val result = sendFile(
                        file = file,
                        hostAddress = clientIP,
                        senderName = senderName,
                        senderAddress = senderAddress
                    )
                    
                    if (result.isSuccess) {
                        result.getOrNull()?.let { results.add(it) }
                        Log.d(TAG, "Successfully sent file to client: $clientIP")
                    } else {
                        val throwable = result.exceptionOrNull()
                        val error = if (throwable is Exception) throwable else Exception("Unknown error sending to $clientIP: ${throwable?.message}")
                        errors.add(error)
                        Log.e(TAG, "Failed to send file to client $clientIP: ${error.message}")
                    }
                } catch (e: Exception) {
                    errors.add(e)
                    Log.e(TAG, "Exception sending file to client $clientIP: ${e.message}")
                }
            }

            // Return success if we sent to at least one client
            if (results.isNotEmpty()) {
                Log.d(TAG, "Successfully sent file to ${results.size}/${activeClientIPs.size} clients")
                Result.success(results)
            } else {
                val combinedError = Exception("Failed to send file to any clients. Errors: ${errors.map { it.message }}")
                Log.e(TAG, "Failed to send file to all clients: ${combinedError.message}")
                Result.failure(combinedError)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendFileToAllClients: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Detect potential clients in the WiFi Direct network
     * This helps identify clients even before they connect to the file server
     */
    suspend fun detectPotentialClients(groupOwnerIP: String) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting client detection from group owner IP: $groupOwnerIP")
            
            // For now, we'll rely on actual connections since clients don't run servers
            // This method can be extended later with ping-based detection or other methods
            
            val currentClients = connectedClients.size
            Log.d(TAG, "Current connected clients: $currentClients")
            
            // Notify that we're actively looking for clients
            _connectionStatus.value = "Looking for connected clients..."
            
            // Reset status after a moment
            kotlinx.coroutines.delay(2000)
            _connectionStatus.value = if (currentClients > 0) {
                "File server running - $currentClients client(s) connected"
            } else {
                "File server running - waiting for clients"
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting potential clients: ${e.message}")
        }
    }

    /**
     * Clear all connected clients (useful for testing/debugging)
     */
    fun clearConnectedClients() {
        connectedClients.clear()
        updateConnectedClientsStateFlow()
        Log.d(TAG, "Cleared all connected clients")
    }

    /**
     * Send a client hello signal to announce presence to group owner
     * This allows clients to appear in the group owner's list without sending a file
     */
    suspend fun sendClientHello(groupOwnerAddress: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending CLIENT_HELLO to group owner: $groupOwnerAddress")
            
            Socket().use { socket ->
                socket.connect(InetSocketAddress(groupOwnerAddress, FILE_PORT), CONNECTION_TIMEOUT)
                Log.d(TAG, "Connected to group owner for CLIENT_HELLO")
                
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                
                // Send CLIENT_HELLO message
                val helloMessage = CLIENT_HELLO_MESSAGE
                val messageBytes = helloMessage.toByteArray(Charsets.UTF_8)
                
                // Send message length first (4 bytes)
                val lengthBuffer = java.nio.ByteBuffer.allocate(4).putInt(messageBytes.size).array()
                outputStream.write(lengthBuffer)
                
                // Send hello message
                outputStream.write(messageBytes)
                outputStream.flush()
                
                Log.d(TAG, "CLIENT_HELLO sent, waiting for acknowledgment...")
                
                // Wait for acknowledgment
                val reader = BufferedReader(InputStreamReader(inputStream))
                val ack = reader.readLine()
                
                Log.d(TAG, "Received acknowledgment: $ack")
                
                if (ack == "HELLO_ACK") {
                    Log.d(TAG, "CLIENT_HELLO successfully acknowledged")
                    Result.success(Unit)
                } else {
                    Log.w(TAG, "Unexpected acknowledgment: $ack")
                    Result.success(Unit) // Still consider it success
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending CLIENT_HELLO: ${e.message}", e)
            Result.failure(e)
        }
    }
}
