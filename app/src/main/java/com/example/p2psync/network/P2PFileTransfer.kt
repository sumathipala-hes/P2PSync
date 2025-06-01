package com.example.p2psync.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Handles P2P file transfer between devices
 */
class P2PFileTransfer {

    companion object {
        private const val TAG = "P2PFileTransfer"
        private const val PORT = 8888
        private const val BUFFER_SIZE = 8192
    }

    data class TransferProgress(
        val fileName: String = "",
        val bytesTransferred: Long = 0,
        val totalBytes: Long = 0,
        val percentage: Int = 0,
        val isComplete: Boolean = false,
        val error: String? = null
    )

    private val _transferProgress = MutableStateFlow(TransferProgress())
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    private val _isTransferring = MutableStateFlow(false)
    val isTransferring: StateFlow<Boolean> = _isTransferring.asStateFlow()

    /**
     * Send a file to the connected peer (client side)
     */
    suspend fun sendFile(file: File, hostAddress: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _isTransferring.value = true
            _transferProgress.value = TransferProgress(
                fileName = file.name,
                totalBytes = file.length()
            )

            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, PORT), 5000)
                
                socket.getOutputStream().use { outputStream ->
                    FileInputStream(file).use { fileInputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesTransferred = 0L
                        var bytesRead: Int

                        while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesTransferred += bytesRead

                            val percentage = ((bytesTransferred * 100) / file.length()).toInt()
                            _transferProgress.value = _transferProgress.value.copy(
                                bytesTransferred = bytesTransferred,
                                percentage = percentage
                            )
                        }

                        outputStream.flush()
                    }
                }
            }

            _transferProgress.value = _transferProgress.value.copy(
                isComplete = true,
                percentage = 100
            )
            
            Log.d(TAG, "File sent successfully: ${file.name}")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Error sending file: ${e.message}", e)
            _transferProgress.value = _transferProgress.value.copy(
                error = e.message
            )
            Result.failure(e)
        } finally {
            _isTransferring.value = false
        }
    }    /**
     * Receive a file from the connected peer (server side)
     */
    suspend fun receiveFile(receiveDirectory: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            _isTransferring.value = true

            ServerSocket(PORT).use { serverSocket ->
                serverSocket.accept().use { clientSocket ->
                    clientSocket.getInputStream().use { inputStream ->
                        // For simplicity, we'll use a default filename
                        // In a real implementation, you might send filename first
                        val fileName = "received_file_${System.currentTimeMillis()}"
                        val receivedFile = File(receiveDirectory, fileName)

                        _transferProgress.value = TransferProgress(
                            fileName = fileName
                        )

                        FileOutputStream(receivedFile).use { fileOutputStream ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesTransferred = 0L
                            var bytesRead: Int

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                fileOutputStream.write(buffer, 0, bytesRead)
                                bytesTransferred += bytesRead

                                _transferProgress.value = _transferProgress.value.copy(
                                    bytesTransferred = bytesTransferred
                                )
                            }

                            fileOutputStream.flush()                        }

                        _transferProgress.value = _transferProgress.value.copy(
                            isComplete = true,
                            totalBytes = receivedFile.length(),
                            percentage = 100
                        )

                        Log.d(TAG, "File received successfully: ${receivedFile.name}")
                        Result.success(receivedFile)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error receiving file: ${e.message}", e)
            _transferProgress.value = _transferProgress.value.copy(
                error = e.message
            )
            Result.failure(e)
        } finally {
            _isTransferring.value = false
        }
    }

    /**
     * Cancel ongoing transfer
     */
    fun cancelTransfer() {
        _isTransferring.value = false
        _transferProgress.value = TransferProgress()
        Log.d(TAG, "Transfer cancelled")
    }

    /**
     * Reset transfer state
     */
    fun resetTransfer() {
        _transferProgress.value = TransferProgress()
        _isTransferring.value = false
    }
}
