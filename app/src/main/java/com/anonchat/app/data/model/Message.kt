package com.anonchat.app.data.model

data class Message(
    val messageId: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val content: String = "",
    val type: String = "text",
    val imageUrl: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val readBy: List<String> = emptyList(),
    // When the recipient read this message (ms-since-epoch). 0 = unread.
    // The 5-minute auto-delete timer starts from THIS moment, not from the
    // send time — unread messages never expire until they are read.
    val readAt: Long = 0,
    val isDeleted: Boolean = false
) {
    fun isSentByCurrentUser(currentUserId: String): Boolean {
        return senderId == currentUserId
    }

    fun isRead(currentUserId: String): Boolean {
        return readBy.contains(currentUserId)
    }

    fun getDisplayContent(): String {
        return if (isDeleted) "This message was deleted" else content
    }
}
