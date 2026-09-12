package com.anonchat.app.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anonchat.app.data.model.User
import com.anonchat.app.data.repository.AuthRepository
import com.anonchat.app.data.repository.UserRepository
import com.anonchat.app.util.Constants
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

    private val _usernameState = MutableLiveData<Resource<Boolean>?>()
    val usernameState: LiveData<Resource<Boolean>?> = _usernameState

    private var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var usernameCheckJob: kotlinx.coroutines.Job? = null

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

    /**
     * Live availability check for the username editor (debounced 350ms).
     * Emits null when the input is too short (status line hidden).
     */
    fun checkUsername(username: String) {
        usernameCheckJob?.cancel()
        val name = username.trim().lowercase()
        if (name.length < Constants.MIN_USERNAME_LENGTH) {
            _usernameState.value = null
            return
        }
        // The user's OWN current username is always valid (saving it is
        // legitimate — e.g. a fresh profile doc) — don't show "taken".
        if (name == _user.value?.username) {
            _usernameState.value = Resource.Success(true)
            return
        }
        usernameCheckJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            _usernameState.value = authRepository.isUsernameAvailable(name)
        }
    }

    /**
     * Save the username. Works even when the profile document doesn't exist
     * (fresh anonymous installs) via userRepository.ensureUsername. Keeps the
     * user's OWN current name valid (skips the taken-check for it).
     */
    fun updateUsername(username: String) {
        val name = username.trim().lowercase()
        if (name.length < Constants.MIN_USERNAME_LENGTH || name.length > Constants.MAX_USERNAME_LENGTH) {
            _updateState.value = UpdateState.Error(
                "Username must be ${Constants.MIN_USERNAME_LENGTH}-${Constants.MAX_USERNAME_LENGTH} characters")
            return
        }
        val current = _user.value?.username
        viewModelScope.launch {
            if (name != current) {
                when (val avail = authRepository.isUsernameAvailable(name)) {
                    is Resource.Success -> if (avail.data == false) {
                        _updateState.value = UpdateState.Error("Username already taken")
                        return@launch
                    }
                    is Resource.Error -> {
                        _updateState.value = UpdateState.Error(avail.message ?: "Availability check failed")
                        return@launch
                    }
                    else -> {}
                }
            }
            val result = userRepository.ensureUsername(
                currentUserId, name, _user.value?.avatarColor ?: "#6C63FF")
            when (result) {
                is Resource.Success -> _updateState.value = UpdateState.UsernameSuccess
                is Resource.Error -> _updateState.value = UpdateState.Error(result.message ?: "Failed to save")
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
        usernameCheckJob?.cancel()
    }

    sealed class UpdateState {
        object Success : UpdateState()
        object UsernameSuccess : UpdateState()
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
