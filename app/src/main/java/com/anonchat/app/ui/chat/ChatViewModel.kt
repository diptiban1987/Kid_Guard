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
                kotlinx.coroutines.delay(5000L) // Re-evaluate read-based expiry every 5 seconds
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
        // Clears the on-screen list; on re-entry messages reload from
        // Firestore and visibility is governed by read-state (see
        // filterVisibleMessagesForUI) — not by sessions.
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
     * - Read-based auto-delete: the 5-minute delete timer starts when the
     *   RECIPIENT reads the message (readAt), NOT when it was sent. Unread
     *   messages stay visible — even across sessions — so the recipient
     *   actually gets to read them. Once read, the message disappears for
     *   BOTH participants 5 minutes later.
     * - Manually deleted messages are always hidden.
     */
    private fun filterVisibleMessagesForUI(allMessages: List<Message>): List<Message> {
        if (allMessages.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        return allMessages.filter { msg ->
            if (msg.isDeleted) return@filter false

            // Recipient of this message = the participant who is not the sender.
            val recipientId = if (msg.senderId == currentUserId) otherUserId else currentUserId
            if (!msg.readBy.contains(recipientId)) {
                // Unread by its recipient → keep visible (no timer running).
                return@filter true
            }

            if (msg.readAt <= 0L) {
                // Read under the old regime (no read timestamp recorded):
                // treat as expired — those messages predate this feature.
                return@filter false
            }

            // Read → visible for 5 minutes after the read moment, then gone.
            (now - msg.readAt) <= (5 * 60 * 1000L)
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
