package com.anonchat.app.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.anonchat.app.R
import com.anonchat.app.databinding.ActivityChatBinding
import com.anonchat.app.util.Constants
import com.anonchat.app.util.TimestampConverter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var viewModel: ChatViewModel
    private lateinit var messageAdapter: MessageAdapter
    private var chatId: String = ""
    private var otherUserId: String = ""
    private var otherUsername: String = ""
    private var otherAvatarColor: String = "#6C63FF"

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.sendImageMessage(applicationContext, uri)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openImagePicker()
        } else {
            Toast.makeText(this, "Permission needed to send images", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getStringExtra("chatId") ?: ""
        otherUserId = intent.getStringExtra("otherUserId") ?: ""
        otherUsername = intent.getStringExtra("otherUsername") ?: "Anonymous"
        otherAvatarColor = intent.getStringExtra("otherAvatarColor") ?: "#6C63FF"

        if (chatId.isEmpty()) {
            finish()
            return
        }

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val factory = ChatViewModelFactory(
            chatRepository = com.anonchat.app.data.repository.ChatRepository(
                FirebaseFirestore.getInstance(),
                FirebaseStorage.getInstance()
            ),
            chatId = chatId,
            currentUserId = userId,
            otherUserId = otherUserId,
            otherUsername = otherUsername,
            otherAvatarColor = otherAvatarColor
        )
        viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]

        setupToolbar()
        setupRecyclerView()
        setupInput()
        observeMessages()
        observeTyping()
        observeOtherUserStatus()
        observeUploadState()

        viewModel.markMessagesAsRead()
    }

    private fun observeUploadState() {
        viewModel.uploadState.observe(this) { state ->
            when (state) {
                is com.anonchat.app.util.Resource.Loading -> {
                    Toast.makeText(this, "Sending photo...", Toast.LENGTH_SHORT).show()
                }
                is com.anonchat.app.util.Resource.Error -> {
                    Toast.makeText(this, state.message ?: "Failed to upload image", Toast.LENGTH_LONG).show()
                }
                is com.anonchat.app.util.Resource.Success -> {
                    Toast.makeText(this, "Photo sent!", Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "@$otherUsername"

        try {
            binding.tvAvatarInitials.setBackgroundColor(android.graphics.Color.parseColor(otherAvatarColor))
        } catch (_: Exception) { }
        binding.tvAvatarInitials.text = otherUsername.take(2).uppercase()
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: "",
            onMessageLongClick = { message ->
                showDeleteDialog(message.messageId)
            },
            onImageClick = { imageUrl ->
                showImagePreviewDialog(imageUrl)
            }
        )
        binding.recyclerViewMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerViewMessages.adapter = messageAdapter
    }

    private fun showImagePreviewDialog(imageUrl: String) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_preview)

        val ivFullImage = dialog.findViewById<android.widget.ImageView>(R.id.ivFullImage)
        val btnClosePreview = dialog.findViewById<android.widget.TextView>(R.id.btnClosePreview)
        val previewRoot = dialog.findViewById<android.view.View>(R.id.previewRoot)

        com.bumptech.glide.Glide.with(this)
            .load(imageUrl)
            .placeholder(R.drawable.placeholder_image)
            .into(ivFullImage)

        btnClosePreview.setOnClickListener {
            dialog.dismiss()
        }

        previewRoot.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.sendMessage(text)
                binding.etMessage.text?.clear()
            }
        }

        binding.btnAttachImage.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                openImagePicker()
            } else {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }

        binding.etMessage.addTextChangedListener(object : android.text.TextWatcher {
            private var typingHandler = android.os.Handler(android.os.Looper.getMainLooper())
            private var typingRunnable: Runnable? = null

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setTyping(true)
                typingRunnable?.let { typingHandler.removeCallbacks(it) }
                typingRunnable = Runnable {
                    viewModel.setTyping(false)
                }
                typingHandler.postDelayed(typingRunnable!!, Constants.TYPING_TIMEOUT_MS)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun observeMessages() {
        viewModel.messages.observe(this) { messages ->
            messageAdapter.submitList(messages) {
                if (messages.isNotEmpty()) {
                    binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                }
            }
            viewModel.markMessagesAsRead()
        }
    }

    private fun observeTyping() {
        viewModel.typingStatus.observe(this) { typingMap ->
            val isOtherTyping = typingMap.filter { it.key != FirebaseAuth.getInstance().currentUser?.uid && it.value }
            if (isOtherTyping.isNotEmpty()) {
                binding.tvTypingStatus.text = "typing..."
                binding.tvTypingStatus.visibility = View.VISIBLE
            } else {
                binding.tvTypingStatus.visibility = View.GONE
            }
        }
    }

    private fun observeOtherUserStatus() {
        viewModel.otherUserStatus.observe(this) { user ->
            user?.let {
                if (it.isOnline) {
                    binding.tvOnlineStatus.text = "Online"
                    binding.tvOnlineStatus.visibility = View.VISIBLE
                    binding.onlineDot.visibility = View.VISIBLE
                } else {
                    binding.tvOnlineStatus.text = "Last seen ${TimestampConverter.toRelativeTime(it.lastSeen)}"
                    binding.tvOnlineStatus.visibility = View.VISIBLE
                    binding.onlineDot.visibility = View.GONE
                }
            }
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        imagePickerLauncher.launch(intent)
    }

    private fun showDeleteDialog(messageId: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Message")
            .setMessage("Are you sure you want to delete this message?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteMessage(messageId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        viewModel.setTyping(false)
    }
}
