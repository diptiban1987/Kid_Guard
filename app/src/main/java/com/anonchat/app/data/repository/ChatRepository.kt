package com.anonchat.app.data.repository

import android.net.Uri
import com.anonchat.app.data.model.Chat
import com.anonchat.app.data.model.Message
import com.anonchat.app.data.model.User
import com.anonchat.app.util.Constants
import com.anonchat.app.util.Resource
import com.google.firebase.firestore.*
import kotlin.collections.ArrayList as KotlinArrayList
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

class ChatRepository(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) {

    suspend fun getOrCreateChat(currentUserId: String, otherUserId: String, otherUser: User, currentUser: User): Resource<String> {
        return try {
            val existingChats = firestore.collection(Constants.CHATS_COLLECTION)
                .whereArrayContains("participants", currentUserId)
                .get()
                .await()

            for (doc in existingChats.documents) {
                val chat = doc.toObject(Chat::class.java)
                if (chat != null && chat.participants.contains(otherUserId) && chat.participants.size == 2) {
                    return Resource.Success(chat.chatId)
                }
            }

            val chatRef = firestore.collection(Constants.CHATS_COLLECTION).document()
            val chatId = chatRef.id

            val chat = Chat(
                chatId = chatId,
                participants = listOf(currentUserId, otherUserId),
                participantNames = mapOf(
                    currentUserId to currentUser.username,
                    otherUserId to otherUser.username
                ),
                participantColors = mapOf(
                    currentUserId to currentUser.avatarColor,
                    otherUserId to otherUser.avatarColor
                ),
                lastMessageTimestamp = System.currentTimeMillis()
            )

            chatRef.set(chat).await()
            Resource.Success(chatId)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to create chat")
        }
    }

    fun getConversations(currentUserId: String, onResult: (List<Chat>) -> Unit): ListenerRegistration {
        return firestore.collection(Constants.CHATS_COLLECTION)
            .whereArrayContains("participants", currentUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("ChatRepository", "getConversations error: ${error.message}", error)
                }
                val chats = snapshot?.documents?.mapNotNull { it.toObject(Chat::class.java) } ?: emptyList()
                onResult(chats)
            }
    }


    fun getMessages(chatId: String, onResult: (List<Message>) -> Unit): com.google.firebase.firestore.ListenerRegistration {
        return firestore.collection(Constants.CHATS_COLLECTION)
            .document(chatId)
            .collection(Constants.MESSAGES_COLLECTION)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                val messages = snapshot?.documents?.mapNotNull { it.toObject(Message::class.java) } ?: emptyList()
                onResult(messages)
            }
    }

    suspend fun sendMessage(chatId: String, message: Message): Resource<Unit> {
        return try {
            val msgRef = firestore.collection(Constants.CHATS_COLLECTION)
                .document(chatId)
                .collection(Constants.MESSAGES_COLLECTION)
                .document()

            val msgWithId = message.copy(messageId = msgRef.id)
            msgRef.set(msgWithId).await()

            val chatUpdate = mapOf(
                "lastMessage" to if (message.type == Constants.MESSAGE_TYPE_IMAGE) "\uD83D\uDCF7 Photo" else message.content,
                "lastMessageTimestamp" to message.timestamp,
                "lastMessageSenderId" to message.senderId,
                "lastMessageReadBy" to listOf(message.senderId)
            )
            firestore.collection(Constants.CHATS_COLLECTION)
                .document(chatId)
                .update(chatUpdate)
                .await()

            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to send message")
        }
    }

    suspend fun markMessagesAsRead(chatId: String, currentUserId: String) {
        try {
            val messagesSnapshot = firestore.collection(Constants.CHATS_COLLECTION)
                .document(chatId)
                .collection(Constants.MESSAGES_COLLECTION)
                .whereNotIn("readBy", listOf(currentUserId))
                .get()
                .await()

            val batch = firestore.batch()
            for (doc in messagesSnapshot.documents) {
                val readBy = (doc.get("readBy") as? List<String>)?.toMutableList() ?: mutableListOf()
                if (!readBy.contains(currentUserId)) {
                    readBy.add(currentUserId)
                    batch.update(doc.reference, "readBy", readBy)
                }
            }
            batch.commit().await()

            firestore.collection(Constants.CHATS_COLLECTION)
                .document(chatId)
                .update("lastMessageReadBy", com.google.firebase.firestore.FieldValue.arrayUnion(currentUserId))
                .await()
        } catch (_: Exception) { }
    }

    suspend fun deleteMessage(chatId: String, messageId: String): Resource<Unit> {
        return try {
            firestore.collection(Constants.CHATS_COLLECTION)
                .document(chatId)
                .collection(Constants.MESSAGES_COLLECTION)
                .document(messageId)
                .update("isDeleted", true)
                .await()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to delete message")
        }
    }

    suspend fun uploadImage(context: android.content.Context, uri: Uri, chatId: String): Resource<String> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Resource.Error("Unable to open selected image")

            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                ?: return Resource.Error("Unable to decode selected image")
            try { inputStream.close() } catch (_: Exception) {}

            // Scale down bitmap to max 800px so it is fast and compact
            val maxDim = 800
            val width = originalBitmap.width
            val height = originalBitmap.height
            val scaledBitmap = if (width > maxDim || height > maxDim) {
                val ratio = width.toFloat() / height.toFloat()
                val targetW = if (ratio > 1) maxDim else (maxDim * ratio).toInt()
                val targetH = if (ratio > 1) (maxDim / ratio).toInt() else maxDim
                android.graphics.Bitmap.createScaledBitmap(originalBitmap, targetW, targetH, true)
            } else {
                originalBitmap
            }

            val baos = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos)
            val bytes = baos.toByteArray()

            // Try Firebase Storage first
            try {
                val storageRef = storage.reference
                val imageRef = storageRef.child("chat_images/$chatId/${System.currentTimeMillis()}.jpg")
                val metadata = com.google.firebase.storage.StorageMetadata.Builder()
                    .setContentType("image/jpeg")
                    .build()

                val snapshot = imageRef.putBytes(bytes, metadata).await()
                val downloadUrl = snapshot.storage.downloadUrl.await().toString()
                return Resource.Success(downloadUrl)
            } catch (storageEx: Exception) {
                android.util.Log.w("ChatRepository", "Firebase Storage failed, falling back to Base64 data URL: ${storageEx.message}")
            }

            // Fallback: Convert to compact Base64 JPEG Data URL (works 100% reliably with Firestore)
            val base64Str = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64Str"
            Resource.Success(dataUrl)

        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "Image processing failed: ${e.message}", e)
            Resource.Error(e.message ?: "Failed to attach image")
        }
    }

    suspend fun setTypingStatus(chatId: String, userId: String, isTyping: Boolean) {
        try {
            firestore.collection(Constants.CHATS_COLLECTION)
                .document(chatId)
                .update("typingUsers.$userId", isTyping)
                .await()
        } catch (_: Exception) { }
    }

    fun getTypingStatus(chatId: String, onResult: (Map<String, Boolean>) -> Unit): com.google.firebase.firestore.ListenerRegistration {
        return firestore.collection(Constants.CHATS_COLLECTION)
            .document(chatId)
            .addSnapshotListener { snapshot, _ ->
                val chat = snapshot?.toObject(Chat::class.java)
                onResult(chat?.typingUsers ?: emptyMap())
            }
    }

    fun getChatRealtime(chatId: String, onResult: (Chat?) -> Unit): com.google.firebase.firestore.ListenerRegistration {
        return firestore.collection(Constants.CHATS_COLLECTION)
            .document(chatId)
            .addSnapshotListener { snapshot, _ ->
                onResult(snapshot?.toObject(Chat::class.java))
            }
    }
}
