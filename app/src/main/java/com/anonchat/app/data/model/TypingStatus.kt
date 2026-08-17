package com.anonchat.app.data.model

data class TypingStatus(
    val userId: String = "",
    val chatId: String = "",
    val isTyping: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
