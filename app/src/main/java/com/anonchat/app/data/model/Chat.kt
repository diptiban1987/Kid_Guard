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
}
