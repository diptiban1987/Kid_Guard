package com.anonchat.app.data.model

data class Chat(
    val chatId: String = "",
    val participants: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val participantColors: Map<String, String> = emptyMap(),
    val lastMessage: String = "",
    val lastMessageTimestamp: Long = 0,
    val lastMessageSenderId: String = "",
    val lastMessageReadBy: List<String> = emptyList(),
    // When the last message was read by its recipient (ms-since-epoch).
    // 0 = not read yet (the conversation is still "active").
    val lastMessageReadAt: Long = 0,
    val typingUsers: Map<String, Boolean> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getOtherParticipant(currentUserId: String): String {
        return participants.firstOrNull { it != currentUserId } ?: ""
    }

    fun getOtherName(currentUserId: String): String {
        val otherId = getOtherParticipant(currentUserId)
        return participantNames[otherId] ?: "Anonymous"
    }

    fun getOtherColor(currentUserId: String): String {
        val otherId = getOtherParticipant(currentUserId)
        return participantColors[otherId] ?: "#6C63FF"
    }

    fun getUnreadCount(currentUserId: String): Int {
        return if (lastMessageSenderId != currentUserId && !lastMessageReadBy.contains(currentUserId)) 1 else 0
    }

    /**
     * True when the conversation's last message has "disappeared": the
     * recipient read it AND 5 minutes have passed since the read. Mirrors the
     * read-based auto-delete rule in ChatViewModel — while the last message
     * is still unread (or within its 5-minute post-read window) the
     * conversation is still active. Message content itself is never shown in
     * the list; this only drives the "No active messages" placeholder.
     */
    fun isLastMessageExpired(now: Long = System.currentTimeMillis()): Boolean = when {
        lastMessageReadAt > 0L -> (now - lastMessageReadAt) > (5 * 60 * 1000L)
        lastMessageSenderId.isEmpty() -> (now - lastMessageTimestamp) > (5 * 60 * 1000L)
        else -> {
            // Legacy: read under the old regime (no read timestamp recorded)
            // → those messages predate the read-based feature, treat as gone.
            val recipientId = participants.firstOrNull { it != lastMessageSenderId } ?: ""
            lastMessageReadBy.contains(recipientId)
        }
    }
}
