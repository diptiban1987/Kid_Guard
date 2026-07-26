package com.anonchat.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.anonchat.app.R
import com.anonchat.app.data.repository.AuthRepository
import com.anonchat.app.data.repository.UserRepository
import com.anonchat.app.databinding.ActivityAuthBinding
import com.anonchat.app.ui.main.MainActivity
import com.anonchat.app.util.SecretCodeManager
import com.anonchat.app.util.SecretCodeReceiverManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val viewModel: AuthViewModel by viewModels {
        AuthViewModelFactory(AuthRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance()))
    }

    private var isParentMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val authRepo = AuthRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
        authRepo.setContext(applicationContext)

        if (viewModel.isUserLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setupColorPicker()
        setupClickListeners()
        observeAuthState()
    }

    private fun setupColorPicker() {
        val colors = viewModel.getAvatarColors()
        val colorViews = listOf(
            binding.color1, binding.color2, binding.color3,
            binding.color4, binding.color5, binding.color6,
            binding.color7, binding.color8, binding.color9,
            binding.color10, binding.color11, binding.color12
        )

        colorViews.forEachIndexed { index, view ->
            if (index < colors.size) {
                try {
                    view.setBackgroundColor(android.graphics.Color.parseColor(colors[index]))
                } catch (_: Exception) { }

                view.setOnClickListener {
                    viewModel.selectColor(colors[index])
                    colorViews.forEach { v ->
                        v.scaleX = 1f
                        v.scaleY = 1f
                    }
                    view.scaleX = 1.3f
                    view.scaleY = 1.3f
                }
            }
        }
        val selectedIndex = colors.indexOf(viewModel.getSelectedColor()).coerceAtLeast(0)
        if (selectedIndex < colorViews.size) {
            colorViews[selectedIndex].scaleX = 1.3f
            colorViews[selectedIndex].scaleY = 1.3f
        }
    }

    private fun setupClickListeners() {
        binding.tvAnonTab.setOnClickListener {
            switchToAnonymousMode()
        }

        binding.tvParentTab.setOnClickListener {
            switchToParentMode()
        }

        binding.btnGetStarted.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            viewModel.signInAnonymously(username)
        }

        binding.btnParentLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            viewModel.parentLogin(email, password)
        }

        binding.tvRegisterLink.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            if (email.isBlank() || password.length < 6) {
                binding.tvParentStatus.text = "Enter email and password (min 6 chars) to register"
                binding.tvParentStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                binding.tvParentStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }
            viewModel.parentRegister(email, password)
        }

        binding.tvForgotPassword.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (email.isNotEmpty()) {
                viewModel.sendPasswordReset(email)
            }
            showResetPasswordDialog()
        }

        binding.etUsername.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.checkUsername(s.toString().trim())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun showResetPasswordDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Reset Parent Password")

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val etResetEmail = android.widget.EditText(this).apply {
            hint = "Registered Email"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(binding.etEmail.text.toString().trim())
        }

        val etNewPassword = android.widget.EditText(this).apply {
            hint = "New Password (min 6 characters)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(etResetEmail)
        container.addView(etNewPassword)
        builder.setView(container)

        builder.setPositiveButton("Reset & Login") { dialog, _ ->
            val email = etResetEmail.text.toString().trim()
            val newPassword = etNewPassword.text.toString().trim()
            if (email.isBlank() || newPassword.length < 6) {
                Toast.makeText(this, "Enter valid email & new password (min 6 chars)", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            viewModel.resetPasswordDirect(email, newPassword)
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }

    private fun switchToAnonymousMode() {
        isParentMode = false
        binding.anonymousContainer.visibility = View.VISIBLE
        binding.parentContainer.visibility = View.GONE
        binding.tvSubtitle.text = "Chat anonymously. No phone. No email. Just you."
        binding.tvParentStatus.visibility = View.GONE

        binding.tvAnonTab.setTextColor(0xFFFFFFFF.toInt())
        binding.tvAnonTab.setBackgroundColor(0xFF6C63FF.toInt())
        binding.tvParentTab.setTextColor(0xFFB3FFFFFF.toInt())
        binding.tvParentTab.setBackgroundColor(0x00000000.toInt())
    }

    private fun switchToParentMode() {
        isParentMode = true
        binding.anonymousContainer.visibility = View.GONE
        binding.parentContainer.visibility = View.VISIBLE
        binding.tvSubtitle.text = "Login to monitor and manage devices"
        binding.tvUsernameStatus.visibility = View.GONE

        binding.tvParentTab.setTextColor(0xFFFFFFFF.toInt())
        binding.tvParentTab.setBackgroundColor(0xFF3949AB.toInt())
        binding.tvAnonTab.setTextColor(0xFFB3FFFFFF.toInt())
        binding.tvAnonTab.setBackgroundColor(0x00000000.toInt())
    }

    private fun observeAuthState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authState.collectLatest { state ->
                    when (state) {
                        is AuthViewModel.AuthState.Idle -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnGetStarted.isEnabled = true
                            binding.btnParentLogin.isEnabled = true
                        }
                        is AuthViewModel.AuthState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.btnGetStarted.isEnabled = false
                            binding.btnParentLogin.isEnabled = false
                        }
                        is AuthViewModel.AuthState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            if (SecretCodeManager.isCodeSet(this@AuthActivity)) {
                                SecretCodeReceiverManager.registerDynamicReceiver(this@AuthActivity)
                            }
                            startActivity(Intent(this@AuthActivity, MainActivity::class.java))
                            finish()
                        }
                        is AuthViewModel.AuthState.Info -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnGetStarted.isEnabled = true
                            binding.btnParentLogin.isEnabled = true
                            binding.tvParentStatus.text = state.message
                            binding.tvParentStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                            binding.tvParentStatus.visibility = View.VISIBLE
                        }
                        is AuthViewModel.AuthState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnGetStarted.isEnabled = true
                            binding.btnParentLogin.isEnabled = true
                            if (isParentMode) {
                                binding.tvParentStatus.text = state.message
                                binding.tvParentStatus.setTextColor(ContextCompat.getColor(this@AuthActivity, android.R.color.holo_red_dark))
                                binding.tvParentStatus.visibility = View.VISIBLE
                            } else {
                                Toast.makeText(this@AuthActivity, state.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.usernameAvailable.collectLatest { result ->
                    when (result) {
                        is com.anonchat.app.util.Resource.Success -> {
                            if (result.data == true) {
                                binding.tvUsernameStatus.text = "Username available ✓"
                                binding.tvUsernameStatus.setTextColor(
                                    ContextCompat.getColor(this@AuthActivity, android.R.color.holo_green_dark)
                                )
                            } else {
                                binding.tvUsernameStatus.text = "Username taken ✗"
                                binding.tvUsernameStatus.setTextColor(
                                    ContextCompat.getColor(this@AuthActivity, android.R.color.holo_red_dark)
                                )
                            }
                            binding.tvUsernameStatus.visibility = View.VISIBLE
                        }
                        is com.anonchat.app.util.Resource.Error -> {
                            binding.tvUsernameStatus.visibility = View.GONE
                        }
                        else -> {
                            binding.tvUsernameStatus.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
}

class AuthViewModelFactory(
    private val authRepository: AuthRepository
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AuthViewModel(authRepository) as T
    }
}
