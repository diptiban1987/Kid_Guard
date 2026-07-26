package com.anonchat.app.ui.conversations

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.anonchat.app.databinding.FragmentConversationsBinding
import com.anonchat.app.ui.chat.ChatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ConversationsFragment : Fragment() {

    private var _binding: FragmentConversationsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ConversationsViewModel
    private lateinit var adapter: ConversationsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val factory = ConversationsViewModelFactory(
            com.anonchat.app.data.repository.ChatRepository(
                FirebaseFirestore.getInstance(),
                com.google.firebase.storage.FirebaseStorage.getInstance()
            ),
            userId
        )
        viewModel = ViewModelProvider(this, factory)[ConversationsViewModel::class.java]

        setupRecyclerView(userId)
        observeConversations()
    }

    private fun setupRecyclerView(userId: String) {
        adapter = ConversationsAdapter(userId) { chat ->
            val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra("chatId", chat.chatId)
                putExtra("otherUserId", chat.getOtherParticipant(userId))
                putExtra("otherUsername", chat.getOtherName(userId))
                putExtra("otherAvatarColor", chat.getOtherColor(userId))
            }
            startActivity(intent)
        }
        binding.recyclerViewConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewConversations.adapter = adapter
    }

    private fun observeConversations() {
        viewModel.conversations.observe(viewLifecycleOwner) { chats ->
            adapter.submitList(chats)
            if (chats.isEmpty()) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.recyclerViewConversations.visibility = View.GONE
            } else {
                binding.tvEmptyState.visibility = View.GONE
                binding.recyclerViewConversations.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
