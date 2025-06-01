package com.example.p2psync.data

import java.text.SimpleDateFormat
import java.util.*

/**
 * Data class representing a text message exchanged between P2P devices
 */
data class TextMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val senderName: String,
    val senderAddress: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = true
) {
    fun getFormattedTime(): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    fun getFormattedDateTime(): String {
        val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return formatter.format(Date(timestamp))
    }

    companion object {
        const val MESSAGE_TYPE_TEXT = "TEXT_MESSAGE"
        const val MESSAGE_SEPARATOR = "|||"
        const val FIELD_SEPARATOR = ":::"

        fun fromSerializedString(data: String): TextMessage? {
            return try {
                val parts = data.split(FIELD_SEPARATOR)
                if (parts.size >= 5) {
                    TextMessage(
                        id = parts[0],
                        content = parts[1],
                        senderName = parts[2],
                        senderAddress = parts[3],
                        timestamp = parts[4].toLong(),
                        isOutgoing = false // Received message
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toSerializedString(): String {
        return "$id$FIELD_SEPARATOR$content$FIELD_SEPARATOR$senderName$FIELD_SEPARATOR$senderAddress$FIELD_SEPARATOR$timestamp"
    }
}
