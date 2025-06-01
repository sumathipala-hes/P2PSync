package com.example.p2psync.network

import android.util.Log
import com.example.p2psync.data.TextMessage
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
 * Service for sending and receiving text messages over P2P connection with bidirectional support
 */
class P2PTextMessaging {

    companion object {
        private const val TAG = "P2PTextMessaging"
        private const val TEXT_PORT = 8889 // Different port from file transfer
        private const val CONNECTION_TIMEOUT = 5000
        private const val KEEP_ALIVE_MESSAGE = "KEEP_ALIVE"
        private const val KEEP_ALIVE_INTERVAL = 30000L // 30 seconds
    }

    private val _messages = MutableStateFlow<List<TextMessage>>(emptyList())
    val messages: StateFlow<List<TextMessage>> = _messages.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Not Connected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private var messageServerSocket: ServerSocket? = null
    private var isServerRunning = false
    private var serverJob: Job? = null
    
    // Store active connections for bidirectional communication
    private val activeConnections = ConcurrentHashMap<String, Socket>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())    /**
     * Start listening for incoming text messages (server mode)
     */
    suspend fun startMessageServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isServerRunning) {
                Log.d(TAG, "Message server already running")
                return@withContext Result.success(Unit)
            }

            messageServerSocket = ServerSocket(TEXT_PORT)
            isServerRunning = true
            _isListening.value = true
            _connectionStatus.value = "Listening for messages..."

            Log.d(TAG, "Message server started on port $TEXT_PORT")

            // Start server in a coroutine job
            serverJob = coroutineScope.launch {
                try {
                    while (isServerRunning && messageServerSocket?.isClosed == false) {
                        try {
                            val clientSocket = messageServerSocket?.accept()
                            clientSocket?.let { socket ->
                                val clientAddress = socket.remoteSocketAddress.toString()
                                Log.d(TAG, "New client connected: $clientAddress")
                                
                                // Store the connection for bidirectional communication
                                activeConnections[clientAddress] = socket
                                
                                // Handle this connection in a separate coroutine
                                val connectionJob = launch {
                                    handlePersistentConnection(socket, clientAddress)
                                }
                                connectionJobs[clientAddress] = connectionJob
                            }
                        } catch (e: Exception) {
                            if (isServerRunning) {
                                Log.e(TAG, "Error accepting message connection: ${e.message}")
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
            Log.e(TAG, "Error starting message server: ${e.message}", e)
            _isListening.value = false
            _connectionStatus.value = "Error starting server"
            Result.failure(e)
        }
    }    /**
     * Stop the message server
     */
    fun stopMessageServer() {
        try {
            isServerRunning = false
            
            // Cancel all connection jobs
            connectionJobs.values.forEach { it.cancel() }
            connectionJobs.clear()
            
            // Close all active connections
            activeConnections.values.forEach { socket ->
                try {
                    socket.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing connection: ${e.message}")
                }
            }
            activeConnections.clear()
            
            // Cancel server job
            serverJob?.cancel()
            
            // Close server socket
            messageServerSocket?.close()
            messageServerSocket = null
            
            _isListening.value = false
            _connectionStatus.value = "Server stopped"
            Log.d(TAG, "Message server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping message server: ${e.message}")
        }
    }    /**
     * Send a text message to the connected peer
     */
    suspend fun sendTextMessage(
        message: String,
        hostAddress: String,
        senderName: String,
        senderAddress: String
    ): Result<TextMessage> = withContext(Dispatchers.IO) {
        try {
            val textMessage = TextMessage(
                content = message,
                senderName = senderName,
                senderAddress = senderAddress,
                isOutgoing = true
            )

            // Try to use existing connection first
            val existingConnection = activeConnections.values.find { socket ->
                socket.remoteSocketAddress.toString().contains(hostAddress) && !socket.isClosed
            }

            val success = if (existingConnection != null) {
                // Use existing persistent connection
                sendMessageThroughSocket(existingConnection, textMessage)
            } else {
                // Create new connection and try to establish persistent connection
                establishConnectionAndSendMessage(hostAddress, textMessage)
            }

            if (success) {
                // Add to local messages list
                addMessage(textMessage)
                Log.d(TAG, "Text message sent successfully")
                Result.success(textMessage)
            } else {
                throw Exception("Failed to send message")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending text message: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Establish connection and send message, then maintain the connection
     */
    private suspend fun establishConnectionAndSendMessage(
        hostAddress: String,
        message: TextMessage
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(hostAddress, TEXT_PORT), CONNECTION_TIMEOUT)
            
            // Send the message
            val success = sendMessageThroughSocket(socket, message)
            
            if (success) {
                // Store this connection for future use
                val connectionKey = socket.remoteSocketAddress.toString()
                activeConnections[connectionKey] = socket
                
                // Start listening on this connection for incoming messages
                val connectionJob = coroutineScope.launch {
                    handlePersistentConnection(socket, connectionKey)
                }
                connectionJobs[connectionKey] = connectionJob
                
                Log.d(TAG, "Established persistent connection to $hostAddress")
            } else {
                socket.close()
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing connection: ${e.message}")
            false
        }
    }

    /**
     * Send message through an existing socket
     */
    private fun sendMessageThroughSocket(socket: Socket, message: TextMessage): Boolean {
        return try {
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
            
            // Send message type header
            writer.println(TextMessage.MESSAGE_TYPE_TEXT)
            // Send serialized message
            writer.println(message.toSerializedString())
            
            writer.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending through socket: ${e.message}")
            false
        }
    }    /**
     * Handle persistent connection for bidirectional communication
     */
    private suspend fun handlePersistentConnection(socket: Socket, connectionKey: String) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            
            Log.d(TAG, "Started persistent connection handler for $connectionKey")
            
            while (!socket.isClosed && socket.isConnected) {
                try {
                    val messageType = reader.readLine()
                    
                    if (messageType == null) {
                        // Connection closed by peer
                        Log.d(TAG, "Connection closed by peer: $connectionKey")
                        break
                    }
                    
                    when (messageType) {
                        TextMessage.MESSAGE_TYPE_TEXT -> {
                            val messageData = reader.readLine()
                            messageData?.let { data ->
                                TextMessage.fromSerializedString(data)?.let { message ->
                                    addMessage(message)
                                    Log.d(TAG, "Received text message from ${message.senderName}")
                                    
                                    // Update connection status
                                    _connectionStatus.value = "Connected - Last message: ${message.getFormattedTime()}"
                                }
                            }
                        }
                        KEEP_ALIVE_MESSAGE -> {
                            // Respond to keep-alive
                            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
                            writer.println("KEEP_ALIVE_ACK")
                            writer.flush()
                        }
                        "KEEP_ALIVE_ACK" -> {
                            // Keep-alive acknowledged
                            Log.v(TAG, "Keep-alive acknowledged by $connectionKey")
                        }
                        else -> {
                            Log.w(TAG, "Unknown message type: $messageType")
                        }
                    }
                } catch (e: Exception) {
                    if (!socket.isClosed) {
                        Log.e(TAG, "Error reading from connection $connectionKey: ${e.message}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in persistent connection handler: ${e.message}")
        } finally {
            // Clean up connection
            try {
                activeConnections.remove(connectionKey)
                connectionJobs.remove(connectionKey)
                if (!socket.isClosed) {
                    socket.close()
                }
                Log.d(TAG, "Cleaned up connection: $connectionKey")
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up connection: ${e.message}")
            }
            
            // Update status if no active connections
            if (activeConnections.isEmpty() && !_isListening.value) {
                _connectionStatus.value = "Not Connected"
            }
        }
    }

    /**
     * Start keep-alive mechanism for active connections
     */
    private fun startKeepAlive() {
        coroutineScope.launch {
            while (isServerRunning || activeConnections.isNotEmpty()) {
                delay(KEEP_ALIVE_INTERVAL)
                
                activeConnections.entries.removeAll { (key, socket) ->
                    try {
                        if (socket.isClosed || !socket.isConnected) {
                            Log.d(TAG, "Removing dead connection: $key")
                            connectionJobs[key]?.cancel()
                            connectionJobs.remove(key)
                            true
                        } else {
                            // Send keep-alive
                            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
                            writer.println(KEEP_ALIVE_MESSAGE)
                            writer.flush()
                            false
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Connection $key appears dead, removing: ${e.message}")
                        connectionJobs[key]?.cancel()
                        connectionJobs.remove(key)
                        true
                    }
                }
            }
        }
    }    /**
     * Add a message to the messages list
     */
    private fun addMessage(message: TextMessage) {
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(message)
        // Sort by timestamp to maintain chronological order
        currentMessages.sortBy { it.timestamp }
        _messages.value = currentMessages
    }

    /**
     * Clear all messages
     */
    fun clearMessages() {
        _messages.value = emptyList()
        Log.d(TAG, "Messages cleared")
    }

    /**
     * Get the total number of messages
     */
    fun getMessageCount(): Int = _messages.value.size

    /**
     * Update connection status
     */
    fun updateConnectionStatus(status: String) {
        _connectionStatus.value = status
    }

    /**
     * Get active connections count
     */
    fun getActiveConnectionsCount(): Int = activeConnections.size    /**
     * Check if there are any active connections
     */
    fun hasActiveConnections(): Boolean = activeConnections.isNotEmpty()

    /**
     * Get peer IP addresses from active connections
     */
    fun getPeerAddresses(): List<String> {
        return activeConnections.keys.map { connectionKey ->
            // Extract IP address from connection key (format: "/IP:PORT")
            connectionKey.substringAfter("/").substringBefore(":")
        }.filter { it.isNotEmpty() }
    }    /**
     * Get the first available peer IP address
     */
    fun getFirstPeerAddress(): String? {
        val peerAddresses = getPeerAddresses()
        return peerAddresses.firstOrNull()
    }

    /**
     * Send message to all connected clients (broadcast from master/group owner)
     */
    suspend fun broadcastTextMessage(
        message: String,
        senderName: String,
        senderAddress: String
    ): Result<TextMessage> = withContext(Dispatchers.IO) {
        try {
            val textMessage = TextMessage(
                content = message,
                senderName = senderName,
                senderAddress = senderAddress,
                isOutgoing = true
            )

            if (activeConnections.isEmpty()) {
                throw Exception("No clients connected for broadcast")
            }

            var successCount = 0
            val totalConnections = activeConnections.size

            // Send to all active connections
            activeConnections.values.forEach { socket ->
                try {
                    if (!socket.isClosed && socket.isConnected) {
                        val success = sendMessageThroughSocket(socket, textMessage)
                        if (success) {
                            successCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to one client: ${e.message}")
                }
            }

            if (successCount > 0) {
                // Add to local messages list
                addMessage(textMessage)
                Log.d(TAG, "Broadcast message sent to $successCount/$totalConnections clients")
                Result.success(textMessage)
            } else {
                throw Exception("Failed to send message to any client")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting text message: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopMessageServer()
        coroutineScope.cancel()
    }
}
