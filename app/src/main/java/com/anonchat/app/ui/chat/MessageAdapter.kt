package com.anonchat.app.ui.chat

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.R
import com.anonchat.app.data.model.Message
import com.anonchat.app.databinding.ItemMessageBinding
import com.anonchat.app.util.Constants
import com.anonchat.app.util.TimestampConverter
import com.bumptech.glide.Glide

class MessageAdapter(
    private val currentUserId: String,
    private val onMessageLongClick: (Message) -> Unit,
    private val onImageClick: (String) -> Unit = {}
) : ListAdapter<Message, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message) {
            val isSentByMe = message.isSentByCurrentUser(currentUserId)

            val params = binding.messageCard.layoutParams as FrameLayout.LayoutParams
            if (isSentByMe) {
                params.gravity = Gravity.END
                binding.messageCard.setCardBackgroundColor(Color.parseColor("#6C63FF"))
                binding.tvMessageText.setTextColor(Color.WHITE)
                binding.tvTimestamp.setTextColor(Color.parseColor("#B3FFFFFF"))
            } else {
                params.gravity = Gravity.START
                binding.messageCard.setCardBackgroundColor(Color.parseColor("#2A2A3E"))
                binding.tvMessageText.setTextColor(Color.WHITE)
                binding.tvTimestamp.setTextColor(Color.parseColor("#B3FFFFFF"))
            }
            binding.messageCard.layoutParams = params

            if (message.isDeleted) {
                binding.tvMessageText.text = "This message was deleted"
                binding.tvMessageText.setTypeface(null, android.graphics.Typeface.ITALIC)
                binding.ivMessageImage.visibility = View.GONE
            } else {
                when (message.type) {
                    Constants.MESSAGE_TYPE_IMAGE -> {
                        binding.ivMessageImage.visibility = View.VISIBLE
                        binding.tvMessageText.visibility = View.GONE
                        Glide.with(binding.root.context)
                            .load(message.imageUrl)
                            .placeholder(R.drawable.placeholder_image)
                            .into(binding.ivMessageImage)

                        binding.ivMessageImage.setOnClickListener {
                            if (message.imageUrl.isNotEmpty()) {
                                onImageClick(message.imageUrl)
                            }
                        }
                    }
                    else -> {
                        binding.ivMessageImage.visibility = View.GONE
                        binding.tvMessageText.visibility = View.VISIBLE
                        binding.tvMessageText.text = message.content
                        binding.tvMessageText.setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                }
            }

            binding.tvTimestamp.text = TimestampConverter.toTime(message.timestamp)

            if (isSentByMe) {
                if (message.readBy.size > 1) {
                    binding.tvReadStatus.text = "✓✓"
                    binding.tvReadStatus.setTextColor(Color.parseColor("#4FC3F7"))
                } else {
                    binding.tvReadStatus.text = "✓"
                    binding.tvReadStatus.setTextColor(Color.parseColor("#B3FFFFFF"))
                }
                binding.tvReadStatus.visibility = View.VISIBLE
            } else {
                binding.tvReadStatus.visibility = View.GONE
            }

            if (!message.isDeleted && isSentByMe) {
                binding.root.setOnLongClickListener {
                    onMessageLongClick(message)
                    true
                }
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.messageId == newItem.messageId
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }
}
