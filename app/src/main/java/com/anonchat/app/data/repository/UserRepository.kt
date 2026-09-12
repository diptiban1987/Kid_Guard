package com.anonchat.app.data.repository

import com.anonchat.app.data.model.User
import com.anonchat.app.util.Constants
import com.anonchat.app.util.Resource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await

class UserRepository(
    private val firestore: FirebaseFirestore
) {

    suspend fun getUserById(userId: String): Resource<User> {
        return try {
            val document = firestore.collection(Constants.USERS_COLLECTION)
                .document(userId)
                .get()
                .await()
            val user = document.toObject(User::class.java)
            if (user != null) {
                Resource.Success(user)
            } else {
                Resource.Error("User not found")
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to get user")
        }
    }

    suspend fun searchUsers(query: String, currentUserId: String): Resource<List<User>> {
        return try {
            val result = firestore.collection(Constants.USERS_COLLECTION)
                .whereGreaterThanOrEqualTo("username", query.lowercase())
                .whereLessThanOrEqualTo("username", query.lowercase() + "\uf8ff")
                .limit(Constants.PAGE_SIZE)
                .get()
                .await()
            val users = result.documents.mapNotNull { it.toObject(User::class.java) }
                .filter { it.userId != currentUserId }
            Resource.Success(users)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Search failed")
        }
    }

    suspend fun updateBio(userId: String, bio: String): Resource<Unit> {
        return try {
            firestore.collection(Constants.USERS_COLLECTION)
                .document(userId)
                .update("bio", bio)
                .await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to update bio")
        }
    }

    suspend fun updateUsername(userId: String, username: String): Resource<Unit> {
        return try {
            firestore.collection(Constants.USERS_COLLECTION)
                .document(userId)
                .update("username", username.lowercase())
                .await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to update username")
        }
    }

    /**
     * Save a username whether or not the profile document exists yet.
     *
     * updateUsername() above uses update(), which FAILS on a missing document
     * (seen live: fresh installs logged into Firebase anonymously have NO
     * users doc — their profile page showed an empty blue avatar square and
     * no @username, and Search could never find them). For those, create the
     * FULL document so initials / online status / search all have what they
     * expect; otherwise just update the username field, preserving bio etc.
     */
    suspend fun ensureUsername(userId: String, username: String, avatarColor: String): Resource<Unit> {
        return try {
            val docRef = firestore.collection(Constants.USERS_COLLECTION).document(userId)
            val doc = docRef.get().await()
            if (doc.exists()) {
                docRef.update("username", username.lowercase()).await()
            } else {
                docRef.set(hashMapOf(
                    "userId" to userId,
                    "username" to username.lowercase(),
                    "avatarColor" to avatarColor,
                    "bio" to "Hey there! I am using AnonChat",
                    "fcmToken" to "",
                    "createdAt" to System.currentTimeMillis(),
                    "isOnline" to true,
                    "lastSeen" to System.currentTimeMillis()
                )).await()
            }
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to save username")
        }
    }

    suspend fun updateAvatarColor(userId: String, color: String): Resource<Unit> {
        return try {
            firestore.collection(Constants.USERS_COLLECTION)
                .document(userId)
                .update("avatarColor", color)
                .await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to update avatar color")
        }
    }

    suspend fun getAllUsersExcept(currentUserId: String): Resource<List<User>> {
        return try {
            val result = firestore.collection(Constants.USERS_COLLECTION)
                .limit(Constants.PAGE_SIZE)
                .get()
                .await()
            val users = result.documents.mapNotNull { it.toObject(User::class.java) }
                .filter { it.userId != currentUserId }
            Resource.Success(users)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to get users")
        }
    }

    fun getUserRealtime(userId: String, onResult: (User?) -> Unit): ListenerRegistration {
        return firestore.collection(Constants.USERS_COLLECTION)
            .document(userId)
            .addSnapshotListener { snapshot, _ ->
                onResult(snapshot?.toObject(User::class.java))
            }
    }
}
