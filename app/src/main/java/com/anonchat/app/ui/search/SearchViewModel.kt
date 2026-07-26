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
        val currentUser = currentUserData ?: return
        _chatState.value = ChatState.Loading

        viewModelScope.launch {
            val result = chatRepository.getOrCreateChat(
                currentUserId = currentUserId,
                otherUserId = otherUser.userId,
                otherUser = otherUser,
                currentUser = currentUser
            )
            when (result) {
                is Resource.Success -> {
                    _chatState.value = ChatState.Success(
                        chatId = result.data ?: "",
                        otherUserId = otherUser.userId,
                        otherUsername = otherUser.username,
                        avatarColor = otherUser.avatarColor
                    )
                }
                is Resource.Error -> {
                    _chatState.value = ChatState.Error(result.message ?: "Failed to start chat")
                }
                else -> {}
            }
        }
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
