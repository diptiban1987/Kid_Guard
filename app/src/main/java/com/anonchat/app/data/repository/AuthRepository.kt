package com.anonchat.app.data.repository

import android.content.Context
import com.anonchat.app.data.model.User
import com.anonchat.app.util.Constants
import com.anonchat.app.util.Resource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {

    val currentUser get() = auth.currentUser
    val currentUserId get() = auth.currentUser?.uid ?: ""

    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context
    }

    fun getContext(): Context? {
        return appContext
    }

    suspend fun anonymousSignIn(): Resource<String> {
        return try {
            val result = auth.signInAnonymously().await()
            Resource.Success(result.user?.uid ?: "")
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Sign in failed")
        }
    }

    suspend fun parentSignIn(email: String, password: String): Resource<String> {
        return try {
            val credential = EmailAuthProvider.getCredential(email, password)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user
            if (user != null) {
                val userMap = hashMapOf(
                    "userId" to user.uid,
                    "username" to email.substringBefore("@"),
                    "avatarColor" to "#3949AB",
                    "bio" to "Parent account",
                    "fcmToken" to "",
                    "createdAt" to System.currentTimeMillis(),
                    "isOnline" to true,
                    "lastSeen" to System.currentTimeMillis(),
                    "role" to "parent"
                )
                firestore.collection(Constants.USERS_COLLECTION)
                    .document(user.uid)
                    .set(userMap)
                    .await()
                Resource.Success(user.uid)
            } else {
                Resource.Error("Sign in failed: no user returned")
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Parent sign-in failed")
        }
    }

    suspend fun parentSignUp(email: String, password: String): Resource<String> {
        return try {
            val result = try {
                auth.createUserWithEmailAndPassword(email, password).await()
            } catch (e: Exception) {
                val credential = EmailAuthProvider.getCredential(email, password)
                auth.signInWithCredential(credential).await()
            }
            val user = result.user
            if (user != null) {
                val userMap = hashMapOf(
                    "userId" to user.uid,
                    "username" to email.substringBefore("@"),
                    "avatarColor" to "#3949AB",
                    "bio" to "Parent account",
                    "fcmToken" to "",
                    "createdAt" to System.currentTimeMillis(),
                    "isOnline" to true,
                    "lastSeen" to System.currentTimeMillis(),
                    "role" to "parent"
                )
                firestore.collection(Constants.USERS_COLLECTION)
                    .document(user.uid)
                    .set(userMap)
                    .await()
                Resource.Success(user.uid)
            } else {
                Resource.Error("Registration failed: no user returned")
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Parent registration failed")
        }
    }

    suspend fun createOrUpdateUser(userId: String, username: String, avatarColor: String): Resource<Unit> {
        return try {
            val userMap = hashMapOf(
                "userId" to userId,
                "username" to username.lowercase(),
                "avatarColor" to avatarColor,
                "bio" to "Hey there! I am using AnonChat",
                "fcmToken" to "",
                "createdAt" to System.currentTimeMillis(),
                "isOnline" to true,
                "lastSeen" to System.currentTimeMillis()
            )
            firestore.collection(Constants.USERS_COLLECTION)
                .document(userId)
                .set(userMap)
                .await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to create user")
        }
    }

    suspend fun isUsernameAvailable(username: String): Resource<Boolean> {
        return try {
            val result = firestore.collection(Constants.USERS_COLLECTION)
                .whereEqualTo("username", username.lowercase())
                .get()
                .await()
            Resource.Success(result.isEmpty)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to check username")
        }
    }

    suspend fun getExistingUserByUsername(username: String): User? {
        return try {
            val result = firestore.collection(Constants.USERS_COLLECTION)
                .whereEqualTo("username", username.lowercase())
                .limit(1)
                .get()
                .await()
            result.documents.firstOrNull()?.toObject(User::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updateOnlineStatus(userId: String, isOnline: Boolean) {
        try {
            val updates = hashMapOf<String, Any>(
                "isOnline" to isOnline,
                "lastSeen" to System.currentTimeMillis()
            )
            firestore.collection(Constants.USERS_COLLECTION)
                .document(userId)
                .update(updates)
                .await()
        } catch (_: Exception) { }
    }

    suspend fun updateFcmToken(userId: String, token: String) {
        try {
            firestore.collection(Constants.USERS_COLLECTION)
                .document(userId)
                .update("fcmToken", token)
                .await()
        } catch (_: Exception) { }
    }

    fun signOut() {
        auth.signOut()
    }
}
