package com.anonchat.app.util

object Constants {
    const val USERS_COLLECTION = "users"
    const val CHATS_COLLECTION = "chats"
    const val MESSAGES_COLLECTION = "messages"
    const val ONLINE_STATUS_COLLECTION = "online_status"

    const val MESSAGE_TYPE_TEXT = "text"
    const val MESSAGE_TYPE_IMAGE = "image"

    const val SHARED_PREF_NAME = "anon_chat_prefs"
    const val KEY_USER_ID = "user_id"
    const val KEY_USERNAME = "username"
    const val KEY_IS_LOGGED_IN = "is_logged_in"

    const val MAX_USERNAME_LENGTH = 20
    const val MIN_USERNAME_LENGTH = 3
    const val MAX_MESSAGE_LENGTH = 1000

    const val TYPING_TIMEOUT_MS = 3000L
    const val ONLINE_TIMEOUT_MS = 60000L

    const val PAGE_SIZE = 20L
    const val IMAGE_MAX_SIZE = 1024 * 1024.toLong()

    const val SECRET_CODE_MIN_LENGTH = 4
    const val SECRET_CODE_MAX_LENGTH = 15
    const val DEFAULT_SECRET_CODE = "1234"
}
