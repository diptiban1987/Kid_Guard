package com.anonchat.app.ui.conversations

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.anonchat.app.data.model.Chat
import com.anonchat.app.data.repository.ChatRepository

class ConversationsViewModel(
    private val chatRepository: ChatRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _conversations = MutableLiveData<List<Chat>>()
    val conversations: LiveData<List<Chat>> = _conversations

    private var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    init {
        loadConversations()
    }

    private fun loadConversations() {
        listenerRegistration = null
        chatRepository.getConversations(currentUserId) { chats ->
            _conversations.value = chats.sortedByDescending { it.lastMessageTimestamp }
        }
    }

    override fun onCleared() {
        super.onCleared()
        listenerRegistration?.remove()
    }
}

class ConversationsViewModelFactory(
    private val chatRepository: ChatRepository,
    private val currentUserId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ConversationsViewModel(chatRepository, currentUserId) as T
    }
}
