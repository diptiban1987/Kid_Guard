package com.anonchat.app.ui.conversations

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.data.model.Chat
import com.anonchat.app.databinding.ItemConversationBinding
import com.anonchat.app.util.TimestampConverter

class ConversationsAdapter(
    private val currentUserId: String,
    private val onChatClick: (Chat) -> Unit
) : ListAdapter<Chat, ConversationsAdapter.ConversationViewHolder>(ChatDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConversationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ConversationViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chat: Chat) {
            val otherName = chat.getOtherName(currentUserId)
            val otherColor = chat.getOtherColor(currentUserId)

            binding.tvUsername.text = otherName

            try {
                binding.tvAvatarInitials.setBackgroundColor(Color.parseColor(otherColor))
            } catch (_: Exception) { }

            binding.tvAvatarInitials.text = otherName.take(2).uppercase()

            if (chat.lastMessage.isNotEmpty()) {
                val prefix = if (chat.lastMessageSenderId == currentUserId) "You: " else ""
                binding.tvLastMessage.text = prefix + chat.lastMessage
            } else {
                binding.tvLastMessage.text = "Start a conversation"
            }

            binding.tvTimestamp.text = TimestampConverter.toRelativeTime(chat.lastMessageTimestamp)

            val isUnread = chat.lastMessageSenderId != currentUserId &&
                    !chat.lastMessageReadBy.contains(currentUserId)

            if (isUnread) {
                binding.unreadIndicator.visibility = android.view.View.VISIBLE
                binding.tvLastMessage.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                binding.unreadIndicator.visibility = android.view.View.GONE
                binding.tvLastMessage.setTypeface(null, android.graphics.Typeface.NORMAL)
            }

            val typingUsers = chat.typingUsers.filter { it.key != currentUserId && it.value }
            if (typingUsers.isNotEmpty()) {
                binding.tvLastMessage.text = "typing..."
                binding.tvLastMessage.setTypeface(null, android.graphics.Typeface.ITALIC)
            }

            binding.root.setOnClickListener { onChatClick(chat) }
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<Chat>() {
        override fun areItemsTheSame(oldItem: Chat, newItem: Chat): Boolean {
            return oldItem.chatId == newItem.chatId
        }

        override fun areContentsTheSame(oldItem: Chat, newItem: Chat): Boolean {
            return oldItem == newItem
        }
    }
}
