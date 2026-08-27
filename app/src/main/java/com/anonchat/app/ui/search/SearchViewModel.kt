package com.anonchat.app.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anonchat.app.data.model.Chat
import com.anonchat.app.data.model.User
import com.anonchat.app.data.repository.ChatRepository
import com.anonchat.app.data.repository.UserRepository
import com.anonchat.app.util.Resource
import kotlinx.coroutines.launch

class SearchViewModel(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _users = MutableLiveData<List<User>>()
    val users: LiveData<List<User>> = _users

    private val _chatState = MutableLiveData<ChatState?>()
    val chatState: LiveData<ChatState?> = _chatState

    private var currentUserData: User? = null

    init {
        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            val result = userRepository.getUserById(currentUserId)
            if (result is Resource.Success) {
                currentUserData = result.data
            }
        }
    }

    fun searchUsers(query: String) {
        viewModelScope.launch {
            val result = userRepository.searchUsers(query, currentUserId)
            if (result is Resource.Success) {
                _users.value = result.data ?: emptyList()
            }
        }
    }

    fun loadAllUsers() {
        viewModelScope.launch {
            val result = userRepository.getAllUsersExcept(currentUserId)
            if (result is Resource.Success) {
                _users.value = result.data ?: emptyList()
            }
        }
    }

    fun startChat(otherUser: User) {
        viewModelScope.launch {
            _chatState.value = ChatState.Loading
            try {
                var currentUser = currentUserData
                if (currentUser == null) {
                    val res = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                        userRepository.getUserById(currentUserId)
                    }
                    if (res is Resource.Success && res.data != null) {
                        currentUser = res.data
                        currentUserData = res.data
                    }
                }
                if (currentUser == null) {
                    val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    val name = authUser?.displayName?.ifBlank { null }
                        ?: authUser?.email?.substringBefore("@")
                        ?: "User"
                    currentUser = User(userId = currentUserId, username = name, avatarColor = "#6C63FF")
                }

                val result = kotlinx.coroutines.withTimeoutOrNull(10000L) {
                    chatRepository.getOrCreateChat(
                        currentUserId = currentUserId,
                        otherUserId = otherUser.userId,
                        otherUser = otherUser,
                        currentUser = currentUser
                    )
                }

                if (result is Resource.Success && !result.data.isNullOrEmpty()) {
                    _chatState.value = ChatState.Success(
                        chatId = result.data,
                        otherUserId = otherUser.userId,
                        otherUsername = otherUser.username,
                        avatarColor = otherUser.avatarColor
                    )
                } else if (result is Resource.Error) {
                    _chatState.value = ChatState.Error(result.message ?: "Failed to start chat")
                } else {
                    _chatState.value = ChatState.Error("Connection timed out. Please try again.")
                }
            } catch (e: Exception) {
                _chatState.value = ChatState.Error(e.message ?: "Error starting chat")
            }
        }
    }

    fun resetChatState() {
        _chatState.value = null
    }

    sealed class ChatState {
        object Loading : ChatState()
        data class Success(
            val chatId: String,
            val otherUserId: String,
            val otherUsername: String,
            val avatarColor: String
        ) : ChatState()
        data class Error(val message: String) : ChatState()
    }
}


class SearchViewModelFactory(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val currentUserId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SearchViewModel(userRepository, chatRepository, currentUserId) as T
    }
}
