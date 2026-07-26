package com.anonchat.app.data.model

data class User(
    val userId: String = "",
    val username: String = "",
    val avatarColor: String = "#6C63FF",
    val bio: String = "",
    val fcmToken: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
) {
    fun getDisplayName(): String = "@$username"

    fun getInitials(): String {
        return if (username.isNotEmpty()) {
            username.substring(0, minOf(2, username.length)).uppercase()
        } else {
            "??"
        }
    }
}
