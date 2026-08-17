package com.anonchat.app.ui.search

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.data.model.User
import com.anonchat.app.databinding.ItemUserBinding

class UserAdapter(
    private val onUserClick: (User) -> Unit
) : ListAdapter<User, UserAdapter.UserViewHolder>(UserDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val binding = ItemUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class UserViewHolder(
        private val binding: ItemUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: User) {
            binding.tvUsername.text = "@${user.username}"
            binding.tvBio.text = user.bio

            try {
                binding.tvAvatarInitials.setBackgroundColor(Color.parseColor(user.avatarColor))
            } catch (_: Exception) { }

            binding.tvAvatarInitials.text = user.getInitials()

            if (user.isOnline) {
                binding.onlineIndicator.visibility = android.view.View.VISIBLE
            } else {
                binding.onlineIndicator.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener { onUserClick(user) }
        }
    }

    class UserDiffCallback : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User): Boolean {
            return oldItem.userId == newItem.userId
        }

        override fun areContentsTheSame(oldItem: User, newItem: User): Boolean {
            return oldItem == newItem
        }
    }
}
