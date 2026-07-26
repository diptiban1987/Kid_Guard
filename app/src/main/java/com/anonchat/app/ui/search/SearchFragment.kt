package com.anonchat.app.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.anonchat.app.databinding.FragmentSearchBinding
import com.anonchat.app.ui.chat.ChatActivity
import com.anonchat.app.data.repository.ChatRepository
import com.anonchat.app.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: SearchViewModel
    private lateinit var userAdapter: UserAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val factory = SearchViewModelFactory(
            UserRepository(FirebaseFirestore.getInstance()),
            ChatRepository(FirebaseFirestore.getInstance(), FirebaseStorage.getInstance()),
            userId
        )
        viewModel = ViewModelProvider(this, factory)[SearchViewModel::class.java]

        setupRecyclerView()
        setupSearch()
        observeState()

        viewModel.loadAllUsers()
    }

    private fun setupRecyclerView() {
        userAdapter = UserAdapter { user ->
            viewModel.startChat(user)
        }
        binding.recyclerViewUsers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewUsers.adapter = userAdapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                if (query.isNotEmpty()) {
                    viewModel.searchUsers(query)
                } else {
                    viewModel.loadAllUsers()
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun observeState() {
        viewModel.users.observe(viewLifecycleOwner) { users ->
            userAdapter.submitList(users)
            if (users.isEmpty()) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.recyclerViewUsers.visibility = View.GONE
            } else {
                binding.tvEmptyState.visibility = View.GONE
                binding.recyclerViewUsers.visibility = View.VISIBLE
            }
        }

        viewModel.chatState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is SearchViewModel.ChatState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                }
                is SearchViewModel.ChatState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                        putExtra("chatId", state.chatId)
                        putExtra("otherUserId", state.otherUserId)
                        putExtra("otherUsername", state.otherUsername)
                        putExtra("otherAvatarColor", state.avatarColor)
                    }
                    startActivity(intent)
                }
                is SearchViewModel.ChatState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
                null -> {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
