package com.anonchat.app.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anonchat.app.data.model.User
import com.anonchat.app.data.repository.AuthRepository
import com.anonchat.app.data.repository.UserRepository
import com.anonchat.app.util.Resource
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _user = MutableLiveData<User?>()
    val user: LiveData<User?> = _user

    private val _updateState = MutableLiveData<UpdateState?>()
    val updateState: LiveData<UpdateState?> = _updateState

    private var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    init {
        loadUserProfile()
    }

    private fun loadUserProfile() {
        listenerRegistration = userRepository.getUserRealtime(currentUserId) { user ->
            _user.value = user
        }
    }

    fun updateBio(bio: String) {
        viewModelScope.launch {
            val result = userRepository.updateBio(currentUserId, bio)
            when (result) {
                is Resource.Success -> _updateState.value = UpdateState.Success
                is Resource.Error -> _updateState.value = UpdateState.Error(result.message ?: "Failed")
                else -> {}
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.updateOnlineStatus(currentUserId, false)
            authRepository.signOut()
        }
    }

    override fun onCleared() {
        super.onCleared()
        listenerRegistration?.remove()
    }

    sealed class UpdateState {
        object Success : UpdateState()
        data class Error(val message: String) : UpdateState()
    }
}

class ProfileViewModelFactory(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val currentUserId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProfileViewModel(userRepository, authRepository, currentUserId) as T
    }
}
