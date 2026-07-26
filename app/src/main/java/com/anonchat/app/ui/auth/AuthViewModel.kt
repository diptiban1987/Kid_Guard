package com.anonchat.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anonchat.app.data.repository.AuthRepository
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.util.Constants
import com.anonchat.app.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState

    private val _usernameAvailable = MutableStateFlow<Resource<Boolean>?>(null)
    val usernameAvailable: StateFlow<Resource<Boolean>?> = _usernameAvailable

    private val avatarColors = listOf(
        "#6C63FF", "#FF6584", "#43A047", "#FB8C00",
        "#E53935", "#8E24AA", "#1E88E5", "#00ACC1",
        "#7CB342", "#F4511E", "#3949AB", "#C0CA33"
    )

    private var selectedColor: String = avatarColors.random()

    fun signInAnonymously(username: String) {
        if (username.length < Constants.MIN_USERNAME_LENGTH) {
            _authState.value = AuthState.Error("Username must be at least ${Constants.MIN_USERNAME_LENGTH} characters")
            return
        }
        if (username.length > Constants.MAX_USERNAME_LENGTH) {
            _authState.value = AuthState.Error("Username must be at most ${Constants.MAX_USERNAME_LENGTH} characters")
            return
        }
        if (!username.all { it.isLetterOrDigit() || it == '_' }) {
            _authState.value = AuthState.Error("Username can only contain letters, numbers, and _")
            return
        }

        viewModelScope.launch {
            _authState.value = AuthState.Loading

            // 1. First ensure user is signed into Firebase Auth so request.auth != null for Firestore queries
            var activeAuthId = authRepository.currentUserId
            if (activeAuthId.isEmpty()) {
                val signInResult = withTimeoutOrNull(20000L) {
                    authRepository.anonymousSignIn()
                } ?: run {
                    _authState.value = AuthState.Error("Sign-in timed out. Please check network connection.")
                    return@launch
                }
                if (signInResult is Resource.Error) {
                    _authState.value = AuthState.Error(signInResult.message ?: "Sign in failed")
                    return@launch
                }
                activeAuthId = (signInResult as Resource.Success).data ?: ""
            }

            if (activeAuthId.isEmpty()) {
                _authState.value = AuthState.Error("Failed to obtain authentication session")
                return@launch
            }

            // 2. Now that request.auth != null, check if username profile exists in Firestore
            val existingUser = try {
                withTimeoutOrNull(15000L) {
                    authRepository.getExistingUserByUsername(username)
                }
            } catch (e: Exception) {
                null
            }

            val finalUserId = if (existingUser != null) {
                // Restore existing profile
                try { authRepository.updateOnlineStatus(existingUser.userId, true) } catch (_: Exception) {}
                existingUser.userId
            } else {
                // New username profile
                val createResult = withTimeoutOrNull(20000L) {
                    authRepository.createOrUpdateUser(activeAuthId, username, selectedColor)
                }
                if (createResult is Resource.Error) {
                    android.util.Log.w("AuthViewModel", "Firestore profile creation warning: ${createResult.message}")
                }
                activeAuthId
            }

            _authState.value = AuthState.Success(finalUserId)
        }
    }

    fun parentLogin(email: String, password: String) {
        if (email.isBlank() || !email.contains("@")) {
            _authState.value = AuthState.Error("Enter a valid email address")
            return
        }
        if (password.length < 6) {
            _authState.value = AuthState.Error("Password must be at least 6 characters")
            return
        }

        viewModelScope.launch {
            _authState.value = AuthState.Loading

            try {
                authRepository.getContext()?.let { CloudConfig.init(it) }
            } catch (_: Exception) { }

            val loginResult = withContext(Dispatchers.IO) {
                try {
                    ApiClient.login(email, password)
                } catch (e: Exception) {
                    ApiClient.Result.Error(e.message ?: "Connection error")
                }
            }

            when (loginResult) {
                is ApiClient.Result.Success -> {
                    var userId = CloudConfig.userId ?: ""

                    val firebaseResult = try {
                        withTimeoutOrNull(15000L) {
                            authRepository.parentSignIn(email, password)
                        }
                    } catch (_: Exception) { null }

                    if (firebaseResult is Resource.Success && !firebaseResult.data.isNullOrEmpty()) {
                        userId = firebaseResult.data
                    } else {
                        try {
                            if (authRepository.currentUserId.isEmpty()) {
                                authRepository.anonymousSignIn()
                            }
                        } catch (_: Exception) { }
                        if (userId.isEmpty()) userId = authRepository.currentUserId
                    }

                    _authState.value = AuthState.Success(userId)
                }
                is ApiClient.Result.Error -> {
                    val errMsg = loginResult.message
                    if (errMsg.contains("invalid", ignoreCase = true) || errMsg.contains("user not found", ignoreCase = true)) {
                        _authState.value = AuthState.Error("Invalid email or password. If you don't have an account, click Register.")
                    } else {
                        _authState.value = AuthState.Error(errMsg)
                    }
                }
            }
        }
    }

    fun parentRegister(email: String, password: String) {
        if (email.isBlank() || !email.contains("@")) {
            _authState.value = AuthState.Error("Enter a valid email address")
            return
        }
        if (password.length < 6) {
            _authState.value = AuthState.Error("Password must be at least 6 characters")
            return
        }

        viewModelScope.launch {
            _authState.value = AuthState.Loading

            try {
                authRepository.getContext()?.let { CloudConfig.init(it) }
            } catch (_: Exception) { }

            val displayName = email.substringBefore("@")
            val regResult = withContext(Dispatchers.IO) {
                try {
                    ApiClient.register(email, password, displayName, role = "parent")
                } catch (e: Exception) {
                    ApiClient.Result.Error(e.message ?: "Connection error")
                }
            }

            when (regResult) {
                is ApiClient.Result.Success -> {
                    var userId = CloudConfig.userId ?: ""
                    try {
                        val fbResult = withTimeoutOrNull(15000L) {
                            authRepository.parentSignUp(email, password)
                        }
                        if (fbResult is Resource.Success && !fbResult.data.isNullOrEmpty()) {
                            userId = fbResult.data
                        }
                    } catch (_: Exception) { }

                    if (userId.isEmpty()) {
                        try {
                            if (authRepository.currentUserId.isEmpty()) {
                                authRepository.anonymousSignIn()
                            }
                        } catch (_: Exception) { }
                        userId = authRepository.currentUserId
                    }

                    _authState.value = AuthState.Success(userId)
                }
                is ApiClient.Result.Error -> {
                    val errMsg = regResult.message
                    if (errMsg.contains("already exists", ignoreCase = true) ||
                        errMsg.contains("already registered", ignoreCase = true) ||
                        errMsg.contains("user exists", ignoreCase = true) ||
                        errMsg.contains("email taken", ignoreCase = true)
                    ) {
                        _authState.value = AuthState.Error("You have already registered! Please enter your password and click Parent Login.")
                    } else {
                        val loginTry = withContext(Dispatchers.IO) {
                            try { ApiClient.login(email, password) } catch (_: Exception) { null }
                        }
                        if (loginTry is ApiClient.Result.Success) {
                            var uId = CloudConfig.userId ?: ""
                            try {
                                if (authRepository.currentUserId.isEmpty()) {
                                    authRepository.anonymousSignIn()
                                }
                            } catch (_: Exception) { }
                            if (uId.isEmpty()) uId = authRepository.currentUserId
                            _authState.value = AuthState.Success(uId)
                        } else {
                            _authState.value = AuthState.Error(errMsg)
                        }
                    }
                }
            }
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank() || !email.contains("@")) {
            _authState.value = AuthState.Error("Please enter your registered email address above first")
            return
        }

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                authRepository.getContext()?.let { CloudConfig.init(it) }
            } catch (_: Exception) { }

            withContext(Dispatchers.IO) {
                try { ApiClient.requestPasswordReset(email) } catch (_: Exception) {}
            }

            try {
                com.google.firebase.auth.FirebaseAuth.getInstance()
                    .sendPasswordResetEmail(email)
                    .await()
                _authState.value = AuthState.Info("Password reset link sent to $email. Check your Inbox/Spam, or use instant reset below.")
            } catch (e: Exception) {
                android.util.Log.e("AuthViewModel", "Password reset failed: ${e.message}", e)
                val msg = e.message ?: ""
                if (msg.contains("no user record", ignoreCase = true) || msg.contains("user not found", ignoreCase = true)) {
                    _authState.value = AuthState.Error("No account found for $email. Please click Register first.")
                } else {
                    _authState.value = AuthState.Info("Password reset requested for $email.")
                }
            }
        }
    }

    fun resetPasswordDirect(email: String, newPassword: String) {
        if (email.isBlank() || !email.contains("@")) {
            _authState.value = AuthState.Error("Please enter a valid email address")
            return
        }
        if (newPassword.length < 6) {
            _authState.value = AuthState.Error("New password must be at least 6 characters")
            return
        }

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                authRepository.getContext()?.let { CloudConfig.init(it) }
            } catch (_: Exception) { }

            withContext(Dispatchers.IO) {
                try {
                    ApiClient.resetPassword(email, "", newPassword)
                } catch (_: Exception) { }
            }

            val firebaseResult = withTimeoutOrNull(30000L) {
                authRepository.parentSignUp(email, newPassword)
            }

            val userId = (firebaseResult as? Resource.Success)?.data ?: CloudConfig.userId ?: ""
            if (userId.isNotEmpty()) {
                _authState.value = AuthState.Success(userId)
            } else {
                parentLogin(email, newPassword)
            }
        }
    }

    fun checkUsername(username: String) {
        if (username.length < Constants.MIN_USERNAME_LENGTH) {
            _usernameAvailable.value = null
            return
        }
        viewModelScope.launch {
            _usernameAvailable.value = authRepository.isUsernameAvailable(username)
        }
    }

    fun selectColor(color: String) {
        selectedColor = color
    }

    fun getSelectedColor(): String = selectedColor

    fun getAvatarColors(): List<String> = avatarColors

    fun isUserLoggedIn(): Boolean = authRepository.currentUser != null

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        data class Success(val userId: String) : AuthState()
        data class Error(val message: String) : AuthState()
        data class Info(val message: String) : AuthState()
    }
}
