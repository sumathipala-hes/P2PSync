package com.example.p2psync

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*

class P2PFileTransfer {
    
    companion object {
        private const val PORT = 8988
        private const val BUFFER_SIZE = 4096
        private const val TAG = "P2PSync-FileTransfer"
    }
    
    interface TransferListener {
        fun onTransferStarted(fileName: String)
        fun onTransferProgress(progress: Int)
        fun onTransferCompleted(fileName: String)
        fun onTransferFailed(error: String)
    }
    
    private var transferListener: TransferListener? = null
    private var transferJob: Job? = null
    
    fun setTransferListener(listener: TransferListener) {
        this.transferListener = listener
    }
    
    // Start as server (Group Owner)
    fun startServer(outputDir: File) {
        transferJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Server started on port $PORT")
                
                while (isActive) {
                    val client = serverSocket.accept()
                    Log.d(TAG, "Client connected: ${client.inetAddress}")
                    
                    handleClientConnection(client, outputDir)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    transferListener?.onTransferFailed("Server error: ${e.message}")
                }
            }
        }
    }
    
    // Connect as client
    fun sendFile(hostAddress: String, file: File) {
        transferJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Connecting to $hostAddress:$PORT")
                val socket = Socket(hostAddress, PORT)
                
                withContext(Dispatchers.Main) {
                    transferListener?.onTransferStarted(file.name)
                }
                
                sendFileToSocket(socket, file)
                
                withContext(Dispatchers.Main) {
                    transferListener?.onTransferCompleted(file.name)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Client error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    transferListener?.onTransferFailed("Send error: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun handleClientConnection(client: Socket, outputDir: File) {
        try {
            val input = DataInputStream(client.getInputStream())
            
            // Read file info
            val fileName = input.readUTF()
            val fileSize = input.readLong()
            
            Log.d(TAG, "Receiving file: $fileName (${fileSize} bytes)")
            
            withContext(Dispatchers.Main) {
                transferListener?.onTransferStarted(fileName)
            }
            
            // Receive file
            val outputFile = File(outputDir, fileName)
            val output = FileOutputStream(outputFile)
            
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesReceived = 0L
            var progress = 0
            
            while (bytesReceived < fileSize) {
                val bytesToRead = minOf(BUFFER_SIZE.toLong(), fileSize - bytesReceived).toInt()
                val bytesRead = input.read(buffer, 0, bytesToRead)
                
                if (bytesRead == -1) break
                
                output.write(buffer, 0, bytesRead)
                bytesReceived += bytesRead
                
                val newProgress = ((bytesReceived * 100) / fileSize).toInt()
                if (newProgress > progress) {
                    progress = newProgress
                    withContext(Dispatchers.Main) {
                        transferListener?.onTransferProgress(progress)
                    }
                }
            }
            
            output.close()
            client.close()
            
            Log.d(TAG, "File received successfully: $fileName")
            
            withContext(Dispatchers.Main) {
                transferListener?.onTransferCompleted(fileName)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}", e)
            client.close()
        }
    }
    
    private fun sendFileToSocket(socket: Socket, file: File) {
        val output = DataOutputStream(socket.getOutputStream())
        val input = FileInputStream(file)
        
        // Send file info
        output.writeUTF(file.name)
        output.writeLong(file.length())
        
        // Send file data
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesSent = 0L
        var progress = 0
        
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            
            output.write(buffer, 0, bytesRead)
            bytesSent += bytesRead
            
            val newProgress = ((bytesSent * 100) / file.length()).toInt()
            if (newProgress > progress) {
                progress = newProgress
                runBlocking {
                    withContext(Dispatchers.Main) {
                        transferListener?.onTransferProgress(progress)
                    }
                }
            }
        }
        
        input.close()
        output.close()
        socket.close()
        
        Log.d(TAG, "File sent successfully: ${file.name}")
    }
    
    fun stopTransfer() {
        transferJob?.cancel()
        transferJob = null
    }
}
