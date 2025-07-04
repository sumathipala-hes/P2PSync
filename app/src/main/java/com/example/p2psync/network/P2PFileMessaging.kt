package com.example.p2psync.network

import android.util.Log
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

    private var messageServerSocket: ServerSocket? = null
    private var isServerRunning = false
    private var serverJob: Job? = null
    
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
        }
    }

    /**
     * Send file to host
     */
    private suspend fun sendFileToHost(hostAddress: String, file: File, fileMessage: FileMessage): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, FILE_PORT), CONNECTION_TIMEOUT)
                
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
            Log.e(TAG, "Error sending file to host: ${e.message}")
            false
        }
    }

    /**
     * Receive file data
     */
    private suspend fun receiveFileData(inputStream: InputStream, fileName: String, fileSize: Long, messageId: String): File? = withContext(Dispatchers.IO) {
        try {
            val receiveDir = getReceiveDirectory()
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
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving file data: ${e.message}", e)
            null
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
}
