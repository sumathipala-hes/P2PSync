package com.example.p2psync.network

import android.util.Log
import android.net.Uri
import android.media.MediaScannerConnection
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
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
 * Data class for file information in sync operations
 */
data class SyncFileInfo(
    val name: String,
    val size: Long,
    val relativePath: String,
    val checksum: String? = null
)

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
        private const val FILE_LIST_REQUEST = "FILE_LIST_REQUEST"
        private const val FILE_LIST_RESPONSE = "FILE_LIST_RESPONSE"
        private const val SYNC_START = "SYNC_START"
        private const val TRIGGER_SYNC_MESSAGE = "TRIGGER_SYNC"
        private const val TWOWAY_SYNC_FOLDER_REQUEST = "TWOWAY_SYNC_FOLDER_REQUEST"
        private const val TWOWAY_SYNC_FOLDER_RESPONSE = "TWOWAY_SYNC_FOLDER_RESPONSE"
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

    // Receive directory state
    private var customReceiveDirectory: File? = null
    private var customReceiveDirectoryUri: Uri? = null

    // Two-way sync folder selection
    private var twoWaySyncFolder: File? = null
    private var twoWaySyncFolderUri: Uri? = null

    // Sync-related state
    private val _syncProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val syncProgress: StateFlow<Map<String, Int>> = _syncProgress.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private var messageServerSocket: ServerSocket? = null
    private var isServerRunning = false
    private var serverJob: Job? = null
    
    private val connectedClients = ConcurrentHashMap<String, ClientInfo>() // clientAddress -> ClientInfo
    private val activeConnections = ConcurrentHashMap<String, Socket>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

                    if (metadataString == FILE_LIST_REQUEST) {
                        Log.d(TAG, "Received FILE_LIST_REQUEST from $clientAddress")
                        try {
                            // Get file list from current receive directory
                            val fileList = if (customReceiveDirectoryUri != null && context != null) {
                                getFileListFromFolderUri(customReceiveDirectoryUri!!)
                            } else {
                                val receiveDir = getCurrentReceiveDirectory()
                                getFileListFromFolder(receiveDir)
                            }
                            
                            // Convert to JSON and send response
                            val fileListJson = fileListToJson(fileList)
                            val responseMessage = "$FILE_LIST_RESPONSE$METADATA_SEPARATOR$fileListJson"
                            val responseBytes = responseMessage.toByteArray(Charsets.UTF_8)
                            
                            // Send response length first
                            val lengthBuffer = java.nio.ByteBuffer.allocate(4).putInt(responseBytes.size).array()
                            outputStream.write(lengthBuffer)
                            outputStream.write(responseBytes)
                            outputStream.flush()
                            
                            Log.d(TAG, "Sent file list response with ${fileList.size} files")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send file list response: ${e.message}")
                        }
                        continue
                    }

                    if (metadataString == TRIGGER_SYNC_MESSAGE) {
                        Log.d(TAG, "Received TRIGGER_SYNC from $clientAddress")
                        // Remote device is requesting this device to start sending files
                        // Send acknowledgment back
                        try {
                            outputStream.write("TRIGGER_ACK\n".toByteArray())
                            outputStream.flush()
                            Log.d(TAG, "Sent TRIGGER_ACK to $clientAddress")
                            
                            // TODO: Implement automatic sync start
                            // This is where we would trigger the local device to start its one-way sync
                            // For now, this is just a placeholder for future implementation
                            Log.d(TAG, "Trigger sync request acknowledged - manual sync start required")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send TRIGGER_ACK: ${e.message}")
                        }
                        continue
                    }

                    if (metadataString == TWOWAY_SYNC_FOLDER_REQUEST) {
                        Log.d(TAG, "Received TWOWAY_SYNC_FOLDER_REQUEST from $clientAddress")
                        try {
                            // Get file list from selected two-way sync folder
                            val fileList = if (twoWaySyncFolderUri != null && context != null) {
                                getFileListFromFolderUri(twoWaySyncFolderUri!!)
                            } else if (twoWaySyncFolder != null) {
                                getFileListFromFolder(twoWaySyncFolder!!)
                            } else {
                                Log.w(TAG, "No two-way sync folder selected, returning empty list")
                                emptyList()
                            }
                            
                            // Convert to JSON and send response
                            val fileListJson = fileListToJson(fileList)
                            val responseMessage = "$TWOWAY_SYNC_FOLDER_RESPONSE$METADATA_SEPARATOR$fileListJson"
                            val responseBytes = responseMessage.toByteArray(Charsets.UTF_8)
                            
                            // Send response length first
                            val lengthBuffer = java.nio.ByteBuffer.allocate(4).putInt(responseBytes.size).array()
                            outputStream.write(lengthBuffer)
                            outputStream.write(responseBytes)
                            outputStream.flush()
                            
                            Log.d(TAG, "Sent two-way sync folder list response with ${fileList.size} files")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send two-way sync folder list response: ${e.message}")
                        }
                        continue
                    }

                    // Handle two-way sync folder listing request
                    if (metadataString == "REQUEST_FOLDER_LISTING") {
                        Log.d(TAG, "Received REQUEST_FOLDER_LISTING from $clientAddress")
                        try {
                            val folderFiles = getTwoWaySyncFolderFiles()
                            Log.d(TAG, "Two-way sync folder files count: ${folderFiles.size}")
                            
                            outputStream.write("FOLDER_LISTING_START\n".toByteArray())
                            
                            folderFiles.forEach { fileInfo ->
                                val fileLine = "${fileInfo.name}$METADATA_SEPARATOR${fileInfo.size}$METADATA_SEPARATOR${fileInfo.relativePath}\n"
                                outputStream.write(fileLine.toByteArray())
                                Log.d(TAG, "Sending file info: ${fileInfo.name} (${fileInfo.relativePath})")
                            }
                            
                            outputStream.write("FOLDER_LISTING_END\n".toByteArray())
                            outputStream.flush()
                            
                            Log.d(TAG, "Sent folder listing with ${folderFiles.size} files")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send folder listing: ${e.message}")
                        }
                        continue
                    }

                    // Handle two-way sync file request
                    if (metadataString.startsWith("REQUEST_TWOWAY_SYNC_FILE")) {
                        Log.d(TAG, "Received REQUEST_TWOWAY_SYNC_FILE from $clientAddress")
                        try {
                            val parts = metadataString.split(METADATA_SEPARATOR)
                            if (parts.size >= 2) {
                                val relativePath = parts[1]
                                val fileData = getTwoWaySyncFile(relativePath)
                                
                                if (fileData != null) {
                                    val response = "TWOWAY_SYNC_FILE_RESPONSE$METADATA_SEPARATOR${fileData.size}\n"
                                    outputStream.write(response.toByteArray())
                                    outputStream.write(fileData)
                                    outputStream.flush()
                                    
                                    Log.d(TAG, "Sent two-way sync file: $relativePath")
                                } else {
                                    outputStream.write("TWOWAY_SYNC_FILE_ERROR\n".toByteArray())
                                    outputStream.flush()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send two-way sync file: ${e.message}")
                        }
                        continue
                    }

                    // Handle two-way sync file receive
                    if (metadataString.startsWith("TWOWAY_SYNC_FILE")) {
                        Log.d(TAG, "Received TWOWAY_SYNC_FILE from $clientAddress")
                        try {
                            val parts = metadataString.split(METADATA_SEPARATOR)
                            if (parts.size >= 3) {
                                val relativePath = parts[1]
                                val fileSize = parts[2].toLongOrNull() ?: 0L
                                
                                // Read file content
                                val fileData = ByteArray(fileSize.toInt())
                                var totalRead = 0
                                
                                while (totalRead < fileSize) {
                                    val bytesRead = inputStream.read(fileData, totalRead, (fileSize - totalRead).toInt())
                                    if (bytesRead == -1) break
                                    totalRead += bytesRead
                                }
                                
                                // Save the file
                                saveTwoWaySyncFile(relativePath, fileData)
                                
                                Log.d(TAG, "Received and saved two-way sync file: $relativePath")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to receive two-way sync file: ${e.message}")
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
            Log.d(TAG, "receiveFileData called for: $fileName")
            Log.d(TAG, "Two-way sync folder: ${twoWaySyncFolder?.absolutePath}")
            Log.d(TAG, "Two-way sync folder URI: $twoWaySyncFolderUri")
            Log.d(TAG, "Custom receive directory URI: $customReceiveDirectoryUri")
            
            // Check if we have a two-way sync folder first (highest priority)
            if (twoWaySyncFolderUri != null && context != null) {
                Log.d(TAG, "Using two-way sync folder URI for receiving file: $fileName")
                return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                    targetUri = twoWaySyncFolderUri!!, targetFolder = null)
            } else if (twoWaySyncFolder != null) {
                Log.d(TAG, "Using two-way sync folder for receiving file: $fileName")
                return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                    targetUri = null, targetFolder = twoWaySyncFolder!!)
            }
            
            // Check if we have a custom URI for receiving (folder sharing)
            val customUri = getCurrentReceiveDirectoryUri()
            
            if (customUri != null && context != null) {
                Log.d(TAG, "Using custom receive folder for file: $fileName")
                return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                    targetUri = customUri, targetFolder = null)
            } else {
                Log.d(TAG, "Using default receive directory for file: $fileName")
                // Use traditional file system approach
                val receiveDir = getCurrentReceiveDirectory()
                return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                    targetUri = null, targetFolder = receiveDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving file data: ${e.message}", e)
            null
        }
    }
    
    /**
     * Receive file and save to both target location and cache directory for dual availability
     */
    private suspend fun receiveFileWithDualSave(
        inputStream: InputStream, 
        fileName: String, 
        fileSize: Long, 
        messageId: String,
        targetUri: Uri?,
        targetFolder: File?
    ): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "receiveFileWithDualSave called for: $fileName")
            Log.d(TAG, "Target URI: $targetUri, Target Folder: ${targetFolder?.absolutePath}")
            
            // Always create a cache file for immediate access
            val cacheFile = File(context?.cacheDir ?: File("/tmp"), fileName)
            cacheFile.parentFile?.mkdirs()
            
            // Buffer for reading data
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesReceived = 0L
            var bytesRead: Int
            
            // Read all data into cache file first
            Log.d(TAG, "Saving to cache: ${cacheFile.absolutePath}")
            FileOutputStream(cacheFile).use { cacheOutputStream ->
                while (bytesReceived < fileSize) {
                    val remainingBytes = (fileSize - bytesReceived).toInt()
                    val bytesToRead = minOf(buffer.size, remainingBytes)
                    
                    bytesRead = inputStream.read(buffer, 0, bytesToRead)
                    if (bytesRead == -1) {
                        Log.w(TAG, "Unexpected end of stream at $bytesReceived/$fileSize bytes")
                        break
                    }
                    
                    cacheOutputStream.write(buffer, 0, bytesRead)
                    bytesReceived += bytesRead

                    val progress = ((bytesReceived * 100) / fileSize).toInt()
                    updateTransferProgress(messageId, progress)
                    
                    if (bytesReceived % (BUFFER_SIZE * 10) == 0L || bytesReceived == fileSize) {
                        Log.v(TAG, "Received $bytesReceived/$fileSize bytes ($progress%)")
                    }
                }
            }

            // Verify cache file was written correctly
            if (cacheFile.length() != fileSize) {
                Log.e(TAG, "Cache file size mismatch. Expected: $fileSize, Actual: ${cacheFile.length()}")
                cacheFile.delete()
                return@withContext null
            }
            
            Log.d(TAG, "File successfully saved to cache: ${cacheFile.absolutePath}")
            
            // Now copy from cache to target location
            var targetFile: File? = null
            
            if (targetUri != null && context != null) {
                // Copy to target URI using Storage Access Framework
                try {
                    val documentFile = DocumentFile.fromTreeUri(context, targetUri)
                    if (documentFile != null && documentFile.canWrite()) {
                        val mimeType = getMimeType(fileName) // Use proper MIME type
                        val newFile = documentFile.createFile(mimeType, fileName)
                        if (newFile != null) {
                            context.contentResolver.openOutputStream(newFile.uri)?.use { targetOutputStream ->
                                FileInputStream(cacheFile).use { cacheInputStream ->
                                    cacheInputStream.copyTo(targetOutputStream)
                                }
                            }
                            Log.d(TAG, "File copied to target URI: ${newFile.uri} with MIME type: $mimeType")
                            targetFile = File(getDisplayPathFromUri(newFile.uri), fileName)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy to target URI: ${e.message}")
                }
            } else if (targetFolder != null) {
                // Copy to target folder using traditional file system
                try {
                    targetFile = File(targetFolder, fileName)
                    targetFile.parentFile?.mkdirs()
                    
                    FileInputStream(cacheFile).use { cacheInputStream ->
                        FileOutputStream(targetFile).use { targetOutputStream ->
                            cacheInputStream.copyTo(targetOutputStream)
                        }
                    }
                    
                    // Set proper permissions and scan for media store
                    setFilePermissions(targetFile)
                    scanFileForMediaStore(targetFile)
                    
                    Log.d(TAG, "File copied to target folder: ${targetFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to copy to target folder: ${e.message}")
                    targetFile = null
                }
            }
            
            // Return the target file if copy was successful, otherwise return cache file
            val resultFile = targetFile ?: cacheFile
            Log.d(TAG, "File received and saved. Primary location: ${resultFile.absolutePath}, Cache: ${cacheFile.absolutePath}")
            
            return@withContext resultFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in receiveFileWithDualSave: ${e.message}", e)
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

            Log.d(TAG, "Receiving file with dual save to custom URI: $folderUri")
            return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                targetUri = folderUri, targetFolder = null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving file to custom URI: ${e.message}", e)
            null
        }
    }

    /**
     * Receive file data for two-way sync (saves to selected sync folder)
     */
    private suspend fun receiveTwoWaySyncFileData(inputStream: InputStream, fileName: String, fileSize: Long, messageId: String): File? = withContext(Dispatchers.IO) {
        try {
            // Use two-way sync folder and also save to cache
            if (twoWaySyncFolderUri != null && context != null) {
                Log.d(TAG, "Receiving two-way sync file data with dual save to URI")
                return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                    targetUri = twoWaySyncFolderUri!!, targetFolder = null)
            } else if (twoWaySyncFolder != null) {
                Log.d(TAG, "Receiving two-way sync file data with dual save to folder")
                return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                    targetUri = null, targetFolder = twoWaySyncFolder!!)
            } else {
                Log.w(TAG, "No two-way sync folder selected, falling back to regular receive directory")
                return@withContext receiveFileData(inputStream, fileName, fileSize, messageId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving two-way sync file: ${e.message}", e)
            null
        }
    }

    /**
     * Receive file to two-way sync folder using Storage Access Framework
     */
    private suspend fun receiveFileToTwoWaySyncUri(inputStream: InputStream, fileName: String, fileSize: Long, messageId: String, folderUri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            if (context == null) return@withContext null
            
            Log.d(TAG, "Receiving two-way sync file with dual save to URI: $folderUri")
            return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                targetUri = folderUri, targetFolder = null)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving file to two-way sync URI: ${e.message}", e)
            null
        }
    }

    /**
     * Get display path from URI for user-friendly display
     */
    private fun getDisplayPathFromUri(uri: Uri): String {
        return try {
            // Try to get the absolute path from the folder URI
            val folderUri = customReceiveDirectoryUri
            if (folderUri != null) {
                return getAbsolutePathFromUri(folderUri)
            }
            
            // Fallback to document file name
            val documentFile = DocumentFile.fromSingleUri(context ?: return "Custom Folder", uri)
            val folderName = documentFile?.name ?: "Selected Folder"
            "Custom Folder/$folderName"
        } catch (e: Exception) {
            Log.w(TAG, "Error getting display path from URI: ${e.message}")
            "Custom Folder"
        }
    }

    /**
     * Get absolute path from URI for display
     */
    private fun getAbsolutePathFromUri(uri: Uri): String {
        return try {
            val uriPath = uri.path
            when {
                uriPath?.contains("/tree/primary:") == true -> {
                    val relativePath = uriPath.substringAfter("/tree/primary:")
                    if (relativePath.isEmpty()) {
                        "/storage/emulated/0"
                    } else {
                        "/storage/emulated/0/$relativePath"
                    }
                }
                uriPath?.contains("/tree/") == true -> {
                    val pathPart = uriPath.substringAfter("/tree/").replace(":", "/")
                    if (pathPart.startsWith("primary/")) {
                        "/storage/emulated/0/${pathPart.substringAfter("primary/")}"
                    } else {
                        "/storage/emulated/0/$pathPart"
                    }
                }
                else -> {
                    val documentFile = DocumentFile.fromTreeUri(context ?: return "Selected Folder", uri)
                    documentFile?.name ?: "Selected Folder"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting absolute path from URI: ${e.message}")
            "Selected Folder"
        }
    }

    /**
     * Get MIME type from file name
     */
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        Log.d(TAG, "getMimeType: File '$fileName' has extension '$extension'")
        
        // First try our custom mapping for common types
        val customMimeType = when (extension) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "apk" -> "application/vnd.android.package-archive"
            else -> null
        }
        
        // If custom mapping didn't find anything, try Android's MimeTypeMap
        val mimeType = customMimeType ?: run {
            val androidMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            androidMimeType ?: "application/octet-stream"
        }
        
        Log.d(TAG, "getMimeType: Detected MIME type '$mimeType' for file '$fileName' (extension: '$extension')")
        return mimeType
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
     * Set folder for two-way sync (File-based)
     */
    fun setTwoWaySyncFolder(folder: File?) {
        twoWaySyncFolder = folder
        Log.d(TAG, "Two-way sync folder set to: ${folder?.absolutePath ?: "null"}")
    }

    /**
     * Set folder for two-way sync (URI-based)
     */
    fun setTwoWaySyncFolderUri(uri: Uri?) {
        twoWaySyncFolderUri = uri
        Log.d(TAG, "Two-way sync folder URI set to: ${uri?.toString() ?: "null"}")
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
            // Check if this is in a two-way sync folder
            twoWaySyncFolder != null && filePath.startsWith(twoWaySyncFolder!!.absolutePath) -> {
                val relativePath = filePath.removePrefix(twoWaySyncFolder!!.absolutePath).removePrefix("/")
                if (relativePath.isEmpty()) {
                    "Two-Way Sync: ${twoWaySyncFolder!!.name}/"
                } else {
                    val pathParts = relativePath.split("/").dropLast(1) // Remove filename
                    if (pathParts.isNotEmpty() && pathParts.first().isNotEmpty()) {
                        "Two-Way Sync: ${twoWaySyncFolder!!.name}/${pathParts.joinToString("/")}/"
                    } else {
                        "Two-Way Sync: ${twoWaySyncFolder!!.name}/"
                    }
                }
            }
            // Check if this is a custom folder URI path (from SAF)
            customReceiveDirectoryUri != null -> {
                getAbsolutePathFromUri(customReceiveDirectoryUri!!) + "/"
            }
            // Check if this is in a two-way sync URI folder 
            twoWaySyncFolderUri != null -> {
                try {
                    val documentFile = context?.let { DocumentFile.fromTreeUri(it, twoWaySyncFolderUri!!) }
                    val folderName = documentFile?.name ?: "Selected Folder"
                    "Two-Way Sync: $folderName/"
                } catch (e: Exception) {
                    "Two-Way Sync Folder/"
                }
            }
            filePath.contains("/storage/emulated/0/Download/P2PSync") -> 
                "Downloads/P2PSync/"
            filePath.contains("/storage/emulated/0/Download") -> 
                "Downloads/"
            filePath.contains("Android/data/com.example.p2psync/files") -> 
                "App Files/"
            filePath.contains("cache") -> 
                "App Cache/"
            else -> {
                // For custom paths, try to show absolute path
                if (filePath.startsWith("/storage/emulated/0/")) {
                    val relativePath = filePath.substringAfter("/storage/emulated/0/")
                    val pathParts = relativePath.split("/")
                    if (pathParts.size >= 2) {
                        "${pathParts.dropLast(1).joinToString("/")}/"
                    } else {
                        "Internal Storage/"
                    }
                } else {
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

    /**
     * Send a trigger message to instruct the remote device to start sending files
     * This is used in two-way sync to trigger the second device to begin its one-way sync
     */
    suspend fun sendTriggerMessage(groupOwnerAddress: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending TRIGGER_SYNC to device: $groupOwnerAddress")
            
            Socket().use { socket ->
                socket.connect(InetSocketAddress(groupOwnerAddress, FILE_PORT), CONNECTION_TIMEOUT)
                Log.d(TAG, "Connected to device for TRIGGER_SYNC")
                
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                
                // Send TRIGGER_SYNC message
                val triggerMessage = TRIGGER_SYNC_MESSAGE
                val messageBytes = triggerMessage.toByteArray(Charsets.UTF_8)
                
                // Send message length first (4 bytes)
                val lengthBuffer = java.nio.ByteBuffer.allocate(4).putInt(messageBytes.size).array()
                outputStream.write(lengthBuffer)
                
                // Send trigger message
                outputStream.write(messageBytes)
                outputStream.flush()
                
                Log.d(TAG, "TRIGGER_SYNC sent, waiting for acknowledgment...")
                
                // Wait for acknowledgment
                val reader = BufferedReader(InputStreamReader(inputStream))
                val ack = reader.readLine()
                
                Log.d(TAG, "Received acknowledgment: $ack")
                
                if (ack == "TRIGGER_ACK") {
                    Log.d(TAG, "TRIGGER_SYNC successfully acknowledged")
                    Result.success(Unit)
                } else {
                    Log.w(TAG, "Unexpected acknowledgment: $ack")
                    Result.success(Unit) // Still consider it success
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending TRIGGER_SYNC: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Folder Synchronization Methods

    /**
     * Get file list from a folder for synchronization
     */
    fun getFileListFromFolder(folder: File): List<SyncFileInfo> {
        val fileList = mutableListOf<SyncFileInfo>()
        
        try {
            if (!folder.exists() || !folder.isDirectory) {
                Log.w(TAG, "Folder does not exist or is not a directory: ${folder.absolutePath}")
                return fileList
            }
            
            folder.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(folder).path
                    fileList.add(
                        SyncFileInfo(
                            name = file.name,
                            size = file.length(),
                            relativePath = relativePath
                        )
                    )
                }
            }
            
            Log.d(TAG, "Found ${fileList.size} files in folder: ${folder.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file list from folder: ${e.message}", e)
        }
        
        return fileList
    }

    /**
     * Get file list from URI-based folder for synchronization
     */
    fun getFileListFromFolderUri(folderUri: Uri): List<SyncFileInfo> {
        val fileList = mutableListOf<SyncFileInfo>()
        
        try {
            if (context == null) {
                Log.w(TAG, "Context is null, cannot read from URI")
                return fileList
            }
            
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            if (documentFile == null || !documentFile.canRead()) {
                Log.w(TAG, "Cannot read from folder URI: $folderUri")
                return fileList
            }
            
            addFilesFromDocumentFile(documentFile, "", fileList)
            
            Log.d(TAG, "Found ${fileList.size} files in URI folder")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file list from URI: ${e.message}", e)
        }
        
        return fileList
    }

    /**
     * Recursively add files from DocumentFile to sync file list
     */
    private fun addFilesFromDocumentFile(
        documentFile: DocumentFile, 
        relativePath: String, 
        fileList: MutableList<SyncFileInfo>
    ) {
        try {
            documentFile.listFiles().forEach { file ->
                if (file.isFile) {
                    val fileName = file.name ?: "unknown"
                    val fullRelativePath = if (relativePath.isEmpty()) fileName else "$relativePath/$fileName"
                    
                    fileList.add(
                        SyncFileInfo(
                            name = fileName,
                            size = file.length(),
                            relativePath = fullRelativePath
                        )
                    )
                } else if (file.isDirectory) {
                    val dirName = file.name ?: "unknown"
                    val newRelativePath = if (relativePath.isEmpty()) dirName else "$relativePath/$dirName"
                    addFilesFromDocumentFile(file, newRelativePath, fileList)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading directory: ${e.message}")
        }
    }

    /**
     * Request file list from remote device
     */
    suspend fun requestFileListFromRemote(hostAddress: String): Result<List<SyncFileInfo>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Requesting file list from $hostAddress")
            
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, FILE_PORT), CONNECTION_TIMEOUT)
                
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                
                // Send file list request
                val requestMessage = FILE_LIST_REQUEST
                val requestBytes = requestMessage.toByteArray(Charsets.UTF_8)
                
                // Send request length first
                val lengthBuffer = java.nio.ByteBuffer.allocate(4).putInt(requestBytes.size).array()
                outputStream.write(lengthBuffer)
                outputStream.write(requestBytes)
                outputStream.flush()
                
                Log.d(TAG, "Sent file list request")
                
                // Read response
                val responseLengthBuffer = ByteArray(4)
                val lengthBytesRead = inputStream.read(responseLengthBuffer)
                if (lengthBytesRead != 4) {
                    throw Exception("Could not read response length")
                }
                
                val responseLength = java.nio.ByteBuffer.wrap(responseLengthBuffer).int
                if (responseLength <= 0 || responseLength > 1024000) { // Max 1MB for file list
                    throw Exception("Invalid response length: $responseLength")
                }
                
                // Read response data
                val responseBuffer = ByteArray(responseLength)
                var totalRead = 0
                while (totalRead < responseLength) {
                    val bytesRead = inputStream.read(responseBuffer, totalRead, responseLength - totalRead)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                }
                
                if (totalRead != responseLength) {
                    throw Exception("Could not read complete response")
                }
                
                val responseString = String(responseBuffer, Charsets.UTF_8)
                Log.d(TAG, "Received file list response: ${responseString.length} characters")
                
                // Parse response
                if (responseString.startsWith(FILE_LIST_RESPONSE)) {
                    val fileListJson = responseString.substringAfter("$FILE_LIST_RESPONSE$METADATA_SEPARATOR")
                    val fileList = parseFileListFromJson(fileListJson)
                    Result.success(fileList)
                } else {
                    throw Exception("Invalid response format")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting file list from remote: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Request file list from remote device's selected two-way sync folder
     */
    suspend fun requestTwoWaySyncFolderFiles(hostAddress: String): Result<List<SyncFileInfo>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Requesting two-way sync folder files from $hostAddress")
            
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, FILE_PORT), CONNECTION_TIMEOUT)
                
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                
                // Send two-way sync folder request
                val requestMessage = TWOWAY_SYNC_FOLDER_REQUEST
                val requestBytes = requestMessage.toByteArray(Charsets.UTF_8)
                
                // Send request length first
                val lengthBuffer = java.nio.ByteBuffer.allocate(4).putInt(requestBytes.size).array()
                outputStream.write(lengthBuffer)
                outputStream.write(requestBytes)
                outputStream.flush()
                
                Log.d(TAG, "Sent two-way sync folder request")
                
                // Read response
                val responseLengthBuffer = ByteArray(4)
                val lengthBytesRead = inputStream.read(responseLengthBuffer)
                if (lengthBytesRead != 4) {
                    throw Exception("Could not read response length")
                }
                
                val responseLength = java.nio.ByteBuffer.wrap(responseLengthBuffer).int
                if (responseLength <= 0 || responseLength > 1024000) { // Max 1MB for file list
                    throw Exception("Invalid response length: $responseLength")
                }
                
                // Read response data
                val responseBuffer = ByteArray(responseLength)
                var totalRead = 0
                while (totalRead < responseLength) {
                    val bytesRead = inputStream.read(responseBuffer, totalRead, responseLength - totalRead)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                }
                
                val responseString = String(responseBuffer, Charsets.UTF_8)
                Log.d(TAG, "Received two-way sync folder response: ${responseString.length} characters")
                
                // Parse response
                if (responseString.startsWith(TWOWAY_SYNC_FOLDER_RESPONSE)) {
                    val fileListJson = responseString.substringAfter("$TWOWAY_SYNC_FOLDER_RESPONSE$METADATA_SEPARATOR")
                    val fileList = parseFileListFromJson(fileListJson)
                    Result.success(fileList)
                } else {
                    throw Exception("Invalid response format")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting two-way sync folder files from remote: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Compare two file lists and return files that need to be synced
     * Comparison is based on file name (relative path) and size only
     */
    fun getFilesToSync(sourceFiles: List<SyncFileInfo>, targetFiles: List<SyncFileInfo>): List<SyncFileInfo> {
        val targetFileMap = targetFiles.associateBy { it.relativePath }
        
        return sourceFiles.filter { sourceFile ->
            val targetFile = targetFileMap[sourceFile.relativePath]
            when {
                targetFile == null -> {
                    Log.d(TAG, "File not in target: ${sourceFile.relativePath}")
                    true // File doesn't exist in target
                }
                sourceFile.size != targetFile.size -> {
                    Log.d(TAG, "File size differs: ${sourceFile.relativePath} (${sourceFile.size} vs ${targetFile.size})")
                    true // File size is different
                }
                else -> {
                    Log.d(TAG, "File is up to date: ${sourceFile.relativePath}")
                    false // File is up to date (same name and size)
                }
            }
        }
    }

    /**
     * Simple JSON parsing for file list (basic implementation)
     */
    private fun parseFileListFromJson(json: String): List<SyncFileInfo> {
        val fileList = mutableListOf<SyncFileInfo>()
        
        try {
            // Simple parsing - each line is: name|size|relativePath
            val lines = json.split("\n").filter { it.isNotBlank() }
            
            for (line in lines) {
                val parts = line.split("|")
                if (parts.size >= 3) {
                    fileList.add(
                        SyncFileInfo(
                            name = parts[0],
                            size = parts[1].toLongOrNull() ?: 0L,
                            relativePath = parts[2]
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing file list JSON: ${e.message}")
        }
        
        return fileList
    }

    /**
     * Convert file list to simple JSON format
     */
    private fun fileListToJson(fileList: List<SyncFileInfo>): String {
        return fileList.joinToString("\n") { file ->
            "${file.name}|${file.size}|${file.relativePath}"
        }
    }

    /**
     * Update sync progress
     */
    private fun updateSyncProgress(operation: String, progress: Int) {
        val currentProgress = _syncProgress.value.toMutableMap()
        currentProgress[operation] = progress
        _syncProgress.value = currentProgress
    }

    /**
     * Update sync status
     */
    fun updateSyncStatus(status: String) {
        _syncStatus.value = status
        Log.d(TAG, "Sync status: $status")
    }

    /**
     * Set syncing state
     */
    fun setSyncingState(isSyncing: Boolean) {
        _isSyncing.value = isSyncing
    }

    /**
     * Request remote folder listing for two-way sync
     */
    suspend fun requestRemoteFolderListing(): Result<List<SyncFileInfo>> = withContext(Dispatchers.IO) {
        try {
            val connectedClient = activeConnections.values.firstOrNull()
            if (connectedClient == null) {
                return@withContext Result.failure(Exception("No connected client"))
            }

            val output = connectedClient.getOutputStream()
            val input = connectedClient.getInputStream()

            // Send request for folder listing
            Log.d(TAG, "Sending REQUEST_FOLDER_LISTING to remote")
            output.write("REQUEST_FOLDER_LISTING\n".toByteArray())
            output.flush()

            // Read response
            val reader = input.bufferedReader()
            val response = reader.readLine()
            Log.d(TAG, "Remote response: $response")
            if (response == "FOLDER_LISTING_START") {
                val files = mutableListOf<SyncFileInfo>()
                
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line == "FOLDER_LISTING_END") {
                        Log.d(TAG, "Received FOLDER_LISTING_END")
                        break
                    }
                    
                    line?.let { fileLine ->
                        Log.d(TAG, "Received file line: $fileLine")
                        val parts = fileLine.split(METADATA_SEPARATOR)
                        if (parts.size >= 3) {
                            val fileInfo = SyncFileInfo(
                                name = parts[0],
                                size = parts[1].toLongOrNull() ?: 0L,
                                relativePath = parts[2]
                            )
                            files.add(fileInfo)
                            Log.d(TAG, "Added file: ${fileInfo.name} (${fileInfo.relativePath})")
                        }
                    }
                }
                
                Log.d(TAG, "Total files received: ${files.size}")
                Result.success(files)
            } else {
                Result.failure(Exception("Invalid response from remote"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting remote folder listing", e)
            Result.failure(e)
        }
    }

    /**
     * Send a file for two-way sync
     */
    suspend fun sendTwoWaySyncFile(file: File, relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val connectedClient = activeConnections.values.firstOrNull()
            if (connectedClient == null) {
                return@withContext Result.failure(Exception("No connected client"))
            }

            val output = connectedClient.getOutputStream()
            
            // Send file header
            val header = "TWOWAY_SYNC_FILE${METADATA_SEPARATOR}${relativePath}${METADATA_SEPARATOR}${file.length()}\n"
            output.write(header.toByteArray())
            output.flush()

            // Send file content
            file.inputStream().use { fileInput ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (fileInput.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
            output.flush()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending two-way sync file: $relativePath", e)
            Result.failure(e)
        }
    }

    /**
     * Send file data for two-way sync (for DocumentFile-based folders)
     */
    suspend fun sendTwoWaySyncFileData(fileData: ByteArray, relativePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val connectedClient = activeConnections.values.firstOrNull()
            if (connectedClient == null) {
                return@withContext Result.failure(Exception("No connected client"))
            }

            val output = connectedClient.getOutputStream()
            
            // Send file header
            val header = "TWOWAY_SYNC_FILE${METADATA_SEPARATOR}${relativePath}${METADATA_SEPARATOR}${fileData.size}\n"
            output.write(header.toByteArray())
            output.flush()

            // Send file content
            output.write(fileData)
            output.flush()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending two-way sync file data: $relativePath", e)
            Result.failure(e)
        }
    }

    /**
     * Request a file for two-way sync
     */
    suspend fun requestTwoWaySyncFile(relativePath: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val connectedClient = activeConnections.values.firstOrNull()
            if (connectedClient == null) {
                return@withContext Result.failure(Exception("No connected client"))
            }

            val output = connectedClient.getOutputStream()
            val input = connectedClient.getInputStream()

            // Send request for specific file
            output.write("REQUEST_TWOWAY_SYNC_FILE${METADATA_SEPARATOR}$relativePath\n".toByteArray())
            output.flush()

            // Read response header
            val response = input.bufferedReader().readLine()
            if (response?.startsWith("TWOWAY_SYNC_FILE_RESPONSE") == true) {
                val parts = response.split(METADATA_SEPARATOR)
                if (parts.size >= 2) {
                    val fileSize = parts[1].toLongOrNull() ?: 0L
                    
                    // Read file content
                    val fileData = ByteArray(fileSize.toInt())
                    var totalRead = 0
                    
                    while (totalRead < fileSize) {
                        val bytesRead = input.read(fileData, totalRead, (fileSize - totalRead).toInt())
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                    }
                    
                    Result.success(fileData)
                } else {
                    Result.failure(Exception("Invalid file response format"))
                }
            } else {
                Result.failure(Exception("File not found or error on remote"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting two-way sync file: $relativePath", e)
            Result.failure(e)
        }
    }

    /**
     * Get folder files for two-way sync
     */
    private fun getTwoWaySyncFolderFiles(): List<SyncFileInfo> {
        val files = mutableListOf<SyncFileInfo>()
        
        try {
            if (twoWaySyncFolderUri != null && context != null) {
                // Handle DocumentFile-based two-way sync folder
                val documentFile = DocumentFile.fromTreeUri(context, twoWaySyncFolderUri!!)
                documentFile?.let { doc ->
                    addTwoWaySyncDocumentFiles(doc, "", files)
                }
            } else if (twoWaySyncFolder != null && twoWaySyncFolder!!.exists()) {
                // Handle File-based two-way sync folder
                twoWaySyncFolder!!.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val relativePath = file.relativeTo(twoWaySyncFolder!!).path
                        files.add(SyncFileInfo(
                            name = file.name,
                            size = file.length(),
                            relativePath = relativePath
                        ))
                    }
                }
            } else {
                Log.w(TAG, "No two-way sync folder selected - twoWaySyncFolder: $twoWaySyncFolder, twoWaySyncFolderUri: $twoWaySyncFolderUri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting two-way sync folder files", e)
        }
        
        return files
    }

    /**
     * Add DocumentFile files recursively for two-way sync
     */
    private fun addTwoWaySyncDocumentFiles(documentFile: DocumentFile?, relativePath: String, files: MutableList<SyncFileInfo>) {
        documentFile?.listFiles()?.forEach { file ->
            if (file.isFile) {
                val currentPath = if (relativePath.isEmpty()) file.name ?: "" else "$relativePath/${file.name}"
                files.add(SyncFileInfo(
                    name = file.name ?: "",
                    size = file.length(),
                    relativePath = currentPath
                ))
            } else if (file.isDirectory) {
                val currentPath = if (relativePath.isEmpty()) file.name ?: "" else "$relativePath/${file.name}"
                addTwoWaySyncDocumentFiles(file, currentPath, files)
            }
        }
    }

    /**
     * Get file data for two-way sync
     */
    private fun getTwoWaySyncFile(relativePath: String): ByteArray? {
        return try {
            if (twoWaySyncFolderUri != null && context != null) {
                // Handle DocumentFile-based folder for two-way sync
                val documentFile = DocumentFile.fromTreeUri(context, twoWaySyncFolderUri!!)
                val targetFile = findTwoWaySyncDocumentFile(documentFile, relativePath)
                
                if (targetFile != null) {
                    context.contentResolver.openInputStream(targetFile.uri)?.use { input ->
                        input.readBytes()
                    }
                } else {
                    null
                }
            } else if (twoWaySyncFolder != null && twoWaySyncFolder!!.exists()) {
                // Handle File-based folder for two-way sync
                val targetFile = File(twoWaySyncFolder!!, relativePath)
                
                if (targetFile.exists()) {
                    targetFile.readBytes()
                } else {
                    null
                }
            } else {
                Log.w(TAG, "No two-way sync folder selected")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting two-way sync file: $relativePath", e)
            null
        }
    }

    /**
     * Find DocumentFile by relative path
     */
    private fun findTwoWaySyncDocumentFile(documentFile: DocumentFile?, relativePath: String): DocumentFile? {
        if (documentFile == null) return null
        
        val pathParts = relativePath.split("/")
        var current = documentFile
        
        for (part in pathParts) {
            current = current?.findFile(part)
            if (current == null) break
        }
        
        return current
    }

    /**
     * Save received file for two-way sync
     */
    private fun saveTwoWaySyncFile(relativePath: String, fileData: ByteArray) {
        try {
            Log.d(TAG, "saveTwoWaySyncFile: Saving file with relativePath: '$relativePath'")
            
            var savedFile: File? = null
            var displayPath = ""
            
            // Always save to cache first for immediate access
            val fileName = File(relativePath).name
            Log.d(TAG, "saveTwoWaySyncFile: Extracted fileName: '$fileName' from relativePath: '$relativePath'")
            
            val cacheFile = File(context?.cacheDir ?: File("/tmp"), fileName)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(fileData)
            Log.d(TAG, "Saved two-way sync file to cache: ${cacheFile.absolutePath}")
            
            // Use two-way sync folder first (highest priority)
            if (twoWaySyncFolderUri != null && context != null) {
                // Handle DocumentFile-based two-way sync folder
                val documentFile = DocumentFile.fromTreeUri(context, twoWaySyncFolderUri!!)
                saveTwoWaySyncToDocumentFile(documentFile, relativePath, fileData)
                Log.d(TAG, "Saved two-way sync file to URI folder: $relativePath")
                
                // Create a synthetic File for the FileMessage pointing to the target location
                savedFile = File("/storage/emulated/0/TwoWaySync/$relativePath")
                displayPath = "Two-Way Sync: ${documentFile?.name ?: "Selected Folder"}/"
                
            } else if (twoWaySyncFolder != null) {
                // Handle File-based two-way sync folder
                val targetFile = File(twoWaySyncFolder, relativePath)
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(fileData)
                
                // Set proper permissions and scan for media store
                setFilePermissions(targetFile)
                scanFileForMediaStore(targetFile)
                
                Log.d(TAG, "Saved two-way sync file to File folder: ${targetFile.absolutePath}")
                
                savedFile = targetFile
                displayPath = "Two-Way Sync: ${twoWaySyncFolder!!.name}/"
                
            } else if (customReceiveDirectoryUri != null && context != null) {
                // Fallback to custom receive folder
                val documentFile = DocumentFile.fromTreeUri(context, customReceiveDirectoryUri!!)
                saveTwoWaySyncToDocumentFile(documentFile, relativePath, fileData)
                Log.d(TAG, "Saved two-way sync file to custom URI folder: $relativePath")
                
                savedFile = File("/storage/emulated/0/CustomFolder/$relativePath")
                displayPath = "Custom Folder/"
                
            } else {
                // Fallback to default receive directory
                val receiveDir = getCurrentReceiveDirectory()
                val targetFile = File(receiveDir, relativePath)
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(fileData)
                
                // Set proper permissions and scan for media store
                setFilePermissions(targetFile)
                scanFileForMediaStore(targetFile)
                
                Log.d(TAG, "Saved two-way sync file to default folder: ${targetFile.absolutePath}")
                
                savedFile = targetFile
                displayPath = getDisplayPath(targetFile.absolutePath)
            }
            
            // Create FileMessage entry for the received file
            if (savedFile != null) {
                val fileMessage = FileMessage(
                    fileName = fileName,
                    filePath = savedFile.absolutePath,
                    fileSize = fileData.size.toLong(),
                    mimeType = getMimeType(fileName),
                    senderName = "Remote Device",
                    senderAddress = "Unknown",
                    isOutgoing = false,
                    transferStatus = FileMessage.TransferStatus.COMPLETED,
                    progress = 100,
                    displayPath = displayPath
                )
                
                addFileMessage(fileMessage)
                Log.d(TAG, "Created FileMessage for two-way sync file: $fileName (Primary: ${savedFile.absolutePath}, Cache: ${cacheFile.absolutePath})")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving two-way sync file: $relativePath", e)
        }
    }

    /**
     * Save file to DocumentFile for two-way sync
     */
    private fun saveTwoWaySyncToDocumentFile(documentFile: DocumentFile?, relativePath: String, fileData: ByteArray) {
        if (documentFile == null || context == null) return
        
        try {
            val pathParts = relativePath.split("/")
            var current = documentFile
            
            // Navigate/create directory structure
            for (i in 0 until pathParts.size - 1) {
                val dirName = pathParts[i]
                val existingDir = current?.findFile(dirName)
                current = if (existingDir != null && existingDir.isDirectory) {
                    existingDir
                } else {
                    current?.createDirectory(dirName)
                }
            }
            
            // Create/overwrite the file
            val fileName = pathParts.last()
            val mimeType = getMimeType(fileName) // Use proper MIME type instead of generic octet-stream
            
            val existingFile = current?.findFile(fileName)
            val targetFile = if (existingFile != null) {
                existingFile
            } else {
                current?.createFile(mimeType, fileName)
            }
            
            targetFile?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { output ->
                    output.write(fileData)
                }
                
                // Try to trigger media scan for DocumentFile by scanning the parent directory
                triggerMediaScanForDocumentFile(file.uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to document file for two-way sync", e)
        }
    }

    /**
     * Receive file to two-way sync folder using File system
     */
    private suspend fun receiveFileToTwoWaySync(inputStream: InputStream, fileName: String, fileSize: Long, messageId: String): File? = withContext(Dispatchers.IO) {
        try {
            // Use two-way sync folder and also save to cache
            if (twoWaySyncFolder != null) {
                Log.d(TAG, "Receiving two-way sync file with dual save: $fileName to ${twoWaySyncFolder!!.absolutePath}")
                return@withContext receiveFileWithDualSave(inputStream, fileName, fileSize, messageId, 
                    targetUri = null, targetFolder = twoWaySyncFolder!!)
            } else {
                Log.w(TAG, "No two-way sync folder selected, cannot receive file")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving two-way sync file: ${e.message}", e)
            null
        }
    }

    /**
     * Trigger media scan for DocumentFile URIs
     */
    private fun triggerMediaScanForDocumentFile(uri: Uri) {
        context?.let { ctx ->
            try {
                // For DocumentFile URIs, we can't directly scan the file,
                // but we can trigger a general media scan
                ctx.sendBroadcast(
                    android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                        data = uri
                    }
                )
                Log.d(TAG, "Triggered media scan for DocumentFile URI: $uri")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trigger media scan for DocumentFile URI: $uri", e)
            }
        }
    }

    /**
     * Scan file to make it visible in file managers and media store
     */
    private fun scanFileForMediaStore(file: File) {
        context?.let { ctx ->
            try {
                MediaScannerConnection.scanFile(
                    ctx,
                    arrayOf(file.absolutePath),
                    null
                ) { path, uri ->
                    Log.d(TAG, "MediaScanner: File scanned and made visible: $path -> $uri")
                }
                Log.d(TAG, "Initiated media scan for file: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to scan file for media store: ${file.absolutePath}", e)
            }
        }
    }

    /**
     * Set proper file permissions to make it accessible
     */
    private fun setFilePermissions(file: File) {
        try {
            // Make file readable by others
            file.setReadable(true, false)
            file.setWritable(true, false)
            Log.d(TAG, "Set file permissions for: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set file permissions: ${file.absolutePath}", e)
        }
    }
}
