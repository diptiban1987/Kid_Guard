package com.anonchat.app.ui.secretcode

import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.anonchat.app.databinding.ActivitySecretCodeSetupBinding
import com.anonchat.app.util.AppHider
import com.anonchat.app.util.SecretCodeManager
import com.anonchat.app.util.SecretCodeReceiverManager

class SecretCodeSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecretCodeSetupBinding
    private var isSetupMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecretCodeSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isSetupMode = !SecretCodeManager.isCodeSet(this)
        setupUI()
        setupClickListeners()
    }

    private fun setupUI() {
        if (isSetupMode) {
            binding.tvTitle.text = "Set Secret Code"
            binding.tvDescription.text = "Choose a numeric code (4-15 digits).\n• Type digits in Calculator app (e.g. 1234)\n• Or dial *#*#YOUR_CODE#*#* from phone dialer\nMaster key: 11111987"
            binding.btnConfirm.text = "Set Code"
            binding.btnCancel.visibility = View.VISIBLE
        } else {
            binding.tvTitle.text = "Change Secret Code"
            binding.tvDescription.text = "Enter your current code, then set a new one.\n• Type digits in Calculator app (e.g. 1234)\n• Or dial *#*#CODE#*#* from phone dialer\nMaster key: 11111987 (for forgot)"
            binding.btnConfirm.text = "Update Code"
            binding.btnCancel.visibility = View.VISIBLE
            binding.layoutCurrentCode.visibility = View.VISIBLE
        }

        binding.tvCurrentStatus.text = "App Status: Protected by Calculator Disguise"
        binding.tvCurrentStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        binding.btnToggleVisibility.text = "Lock to Calculator UI"
    }

    private fun setupClickListeners() {
        binding.btnConfirm.setOnClickListener {
            val newCode = binding.etNewCode.text.toString().trim()
            val confirmCode = binding.etConfirmCode.text.toString().trim()

            if (!isSetupMode) {
                val currentCode = binding.etCurrentCode.text.toString().trim()
                if (!SecretCodeManager.verifyCode(this, currentCode)) {
                    shakeView(binding.layoutCurrentCode)
                    Toast.makeText(this, "Current code is incorrect", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            if (newCode.length < 4) {
                shakeView(binding.layoutNewCode)
                Toast.makeText(this, "Code must be at least 4 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newCode.length > 15) {
                shakeView(binding.layoutNewCode)
                Toast.makeText(this, "Code must be at most 15 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!newCode.all { it.isDigit() }) {
                shakeView(binding.layoutNewCode)
                Toast.makeText(this, "Code must contain only numbers", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newCode != confirmCode) {
                shakeView(binding.layoutConfirmCode)
                Toast.makeText(this, "Codes don't match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val saved = SecretCodeManager.saveSecretCode(this, newCode)
            if (saved) {
                SecretCodeReceiverManager.updateSecretCodeInManifest(this, newCode)
                Toast.makeText(this, "Secret code set! Type $newCode in Calculator to open", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this, "Failed to save code", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnToggleVisibility.setOnClickListener {
            AppHider.hideApp(this)
        }
    }

    private fun shakeView(view: View) {
        val anim = AlphaAnimation(0.0f, 1.0f).apply {
            duration = 50
            repeatMode = AlphaAnimation.REVERSE
            repeatCount = 5
        }
        view.startAnimation(anim)
        if (view is EditText) {
            view.error = " "
        }
    }
}
