package com.example.p2psync.data

import java.text.SimpleDateFormat
import java.util.*
import java.io.File

/**
 * Data class representing a file message exchanged between P2P devices
 */
data class FileMessage(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val senderName: String,
    val senderAddress: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = true,
    val transferStatus: TransferStatus = TransferStatus.PENDING,
    val progress: Int = 0
) {
    enum class TransferStatus {
        PENDING,
        TRANSFERRING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    fun getFormattedTime(): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    fun getFormattedDateTime(): String {
        val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    fun getFormattedFileSize(): String {
        return when {
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
            fileSize < 1024 * 1024 * 1024 -> "${fileSize / (1024 * 1024)} MB"
            else -> "${fileSize / (1024 * 1024 * 1024)} GB"
        }
    }

    fun canBeOpened(): Boolean {
        return transferStatus == TransferStatus.COMPLETED && filePath.isNotEmpty() && File(filePath).exists()
    }

    fun getFileExtension(): String {
        return fileName.substringAfterLast('.', "").lowercase()
    }

    companion object {
        const val MESSAGE_TYPE_FILE = "FILE_MESSAGE"
        const val MESSAGE_SEPARATOR = "|||"
        const val FIELD_SEPARATOR = ":::"

        fun fromSerializedString(data: String): FileMessage? {
            return try {
                val parts = data.split(FIELD_SEPARATOR)
                if (parts.size >= 7) {
                    FileMessage(
                        id = parts[0],
                        fileName = parts[1],
                        filePath = parts[2],
                        fileSize = parts[3].toLong(),
                        mimeType = parts[4],
                        senderName = parts[5],
                        senderAddress = parts[6],
                        timestamp = if (parts.size > 7) parts[7].toLong() else System.currentTimeMillis(),
                        isOutgoing = false // Received message
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toSerializedString(): String {
        return "$id$FIELD_SEPARATOR$fileName$FIELD_SEPARATOR$filePath$FIELD_SEPARATOR$fileSize$FIELD_SEPARATOR$mimeType$FIELD_SEPARATOR$senderName$FIELD_SEPARATOR$senderAddress$FIELD_SEPARATOR$timestamp"
    }
}
