package com.anonchat.app.ui.chat

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anonchat.app.data.model.Message
import com.anonchat.app.data.model.User
import com.anonchat.app.data.repository.ChatRepository
import com.anonchat.app.data.repository.UserRepository
import com.anonchat.app.util.Constants
import com.anonchat.app.util.Resource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val chatId: String,
    private val currentUserId: String,
    private val otherUserId: String,
    private val otherUsername: String,
    private val otherAvatarColor: String
) : ViewModel() {

    private val _messages = MutableLiveData<List<Message>>()
    val messages: LiveData<List<Message>> = _messages

    private val _typingStatus = MutableLiveData<Map<String, Boolean>>()
    val typingStatus: LiveData<Map<String, Boolean>> = _typingStatus

    private val _otherUserStatus = MutableLiveData<User?>()
    val otherUserStatus: LiveData<User?> = _otherUserStatus

    private var currentUsername = ""
    private var currentAvatarColor = "#6C63FF"

    private var messagesListener: ListenerRegistration? = null
    private var typingListener: ListenerRegistration? = null
    private var otherUserListener: ListenerRegistration? = null

    private var sessionStartTime: Long = System.currentTimeMillis()
    private var rawMessagesList: List<Message> = emptyList()
    private var autoExpiryJob: kotlinx.coroutines.Job? = null

    init {
        loadMessages()
        loadTypingStatus()
        loadOtherUserStatus()
        loadCurrentUser()
        startAutoExpiryTicker()
    }

    private fun startAutoExpiryTicker() {
        autoExpiryJob?.cancel()
        autoExpiryJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(5000L) // Refresh every 5 seconds to auto-remove messages older than 5 min
                if (rawMessagesList.isNotEmpty()) {
                    _messages.postValue(filterVisibleMessagesForUI(rawMessagesList))
                }
            }
        }
    }

    fun refreshMessages() {
        if (rawMessagesList.isNotEmpty()) {
            _messages.value = filterVisibleMessagesForUI(rawMessagesList)
        }
    }

    fun wipeSessionOnExit() {
        sessionStartTime = System.currentTimeMillis()
        _messages.value = emptyList()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            val result = userRepository.getUserById(currentUserId)
            if (result is Resource.Success) {
                currentUsername = result.data?.username ?: ""
                currentAvatarColor = result.data?.avatarColor ?: "#6C63FF"
            }
        }
    }

    private fun loadMessages() {
        messagesListener = chatRepository.getMessages(chatId) { messages ->
            rawMessagesList = messages
            _messages.value = filterVisibleMessagesForUI(messages)
        }
    }

    /**
     * Filter messages for UI display:
     * - Database retains 100% of past chat messages permanently in backend.
     * - Timer-based auto-delete: Messages older than 5 minutes (300,000 ms) automatically disappear.
     * - Auto-wipe on exit: Only messages created during the current active session are displayed.
     */
    private fun filterVisibleMessagesForUI(allMessages: List<Message>): List<Message> {
        if (allMessages.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val fiveMinutesAgo = now - (5 * 60 * 1000L) // 5 minutes in ms

        return allMessages.filter { msg ->
            !msg.isDeleted &&
            msg.timestamp >= fiveMinutesAgo &&
            msg.timestamp >= sessionStartTime
        }
    }

    private fun loadTypingStatus() {
        typingListener = chatRepository.getTypingStatus(chatId) { typingMap ->
            _typingStatus.value = typingMap
        }
    }

    private fun loadOtherUserStatus() {
        otherUserListener = userRepository.getUserRealtime(otherUserId) { user ->
            _otherUserStatus.value = user
        }
    }

    fun sendMessage(text: String) {
        val message = Message(
            chatId = chatId,
            senderId = currentUserId,
            senderName = currentUsername,
            content = text,
            type = Constants.MESSAGE_TYPE_TEXT,
            timestamp = System.currentTimeMillis(),
            readBy = listOf(currentUserId)
        )
        viewModelScope.launch {
            chatRepository.sendMessage(chatId, message)
        }
    }

    private val _uploadState = MutableLiveData<Resource<Unit>>()
    val uploadState: LiveData<Resource<Unit>> = _uploadState

    fun sendImageMessage(context: android.content.Context, uri: Uri) {
        _uploadState.value = Resource.Loading()
        viewModelScope.launch {
            val uploadResult = chatRepository.uploadImage(context, uri, chatId)
            when (uploadResult) {
                is Resource.Success -> {
                    val message = Message(
                        chatId = chatId,
                        senderId = currentUserId,
                        senderName = currentUsername,
                        content = "",
                        type = Constants.MESSAGE_TYPE_IMAGE,
                        imageUrl = uploadResult.data ?: "",
                        timestamp = System.currentTimeMillis(),
                        readBy = listOf(currentUserId)
                    )
                    chatRepository.sendMessage(chatId, message)
                    _uploadState.value = Resource.Success(Unit)
                }
                is Resource.Error -> {
                    _uploadState.value = Resource.Error(uploadResult.message ?: "Failed to upload image")
                }
                else -> {}
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessage(chatId, messageId)
        }
    }

    fun markMessagesAsRead() {
        viewModelScope.launch {
            chatRepository.markMessagesAsRead(chatId, currentUserId)
        }
    }

    fun setTyping(isTyping: Boolean) {
        viewModelScope.launch {
            chatRepository.setTypingStatus(chatId, currentUserId, isTyping)
        }
    }

    override fun onCleared() {
        super.onCleared()
        messagesListener?.remove()
        typingListener?.remove()
        otherUserListener?.remove()
    }
}

class ChatViewModelFactory(
    private val chatRepository: ChatRepository,
    private val chatId: String,
    private val currentUserId: String,
    private val otherUserId: String,
    private val otherUsername: String,
    private val otherAvatarColor: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val userRepository = UserRepository(FirebaseFirestore.getInstance())
        return ChatViewModel(
            chatRepository, userRepository,
            chatId, currentUserId, otherUserId, otherUsername, otherAvatarColor
        ) as T
    }
}
