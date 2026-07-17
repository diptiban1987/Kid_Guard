package com.parentalcontrol.app.api

import android.content.Context
import android.content.SharedPreferences
import com.parentalcontrol.app.BuildConfig

object CloudConfig {
    private const val PREFS_NAME = "cloud_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_SERVER_TYPE = "server_type"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_ROLE = "user_role"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_STEALTH_MODE = "stealth_mode"
    const val DEFAULT_SERVER = BuildConfig.SERVER_URL

    const val SERVER_TYPE_AUTO = "auto"
    const val SERVER_TYPE_CLOUD = "cloud"
    const val SERVER_TYPE_LEGACY = "legacy"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var serverType: String
        get() = prefs.getString(KEY_SERVER_TYPE, SERVER_TYPE_AUTO) ?: SERVER_TYPE_AUTO
        set(value) = prefs.edit().putString(KEY_SERVER_TYPE, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, BuildConfig.API_KEY) ?: BuildConfig.API_KEY
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var userRole: String?
        get() = prefs.getString(KEY_USER_ROLE, null)
        set(value) = prefs.edit().putString(KEY_USER_ROLE, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, android.os.Build.DEVICE) ?: android.os.Build.DEVICE
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var stealthMode: Boolean
        get() = prefs.getBoolean(KEY_STEALTH_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_STEALTH_MODE, value).apply()

    var deviceAdminActive: Boolean
        get() = prefs.getBoolean("device_admin_active", false)
        set(value) = prefs.edit().putBoolean("device_admin_active", value).apply()

    var uninstallPassword: String
        get() = prefs.getString("uninstall_password", "") ?: ""
        set(value) = prefs.edit().putString("uninstall_password", value).apply()

    var currentVersionCode: Int
        get() = prefs.getInt("current_version_code", 1)
        set(value) = prefs.edit().putInt("current_version_code", value).apply()

    var pendingUpdatePath: String?
        get() = prefs.getString("pending_update_path", null)
        set(value) = prefs.edit().putString("pending_update_path", value).apply()

    var secretDialerCode: String
        get() = prefs.getString("secret_dialer_code", "132580") ?: "132580"
        set(value) = prefs.edit().putString("secret_dialer_code", value).apply()

    var autoHideEnabled: Boolean
        get() = prefs.getBoolean("auto_hide_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_hide_enabled", value).apply()

    var setupFullyCompleted: Boolean
        get() = prefs.getBoolean("setup_fully_completed", false)
        set(value) = prefs.edit().putBoolean("setup_fully_completed", value).apply()

    val isLoggedIn: Boolean
        get() = accessToken != null || serverType == SERVER_TYPE_LEGACY

    val isChildAccount: Boolean
        get() = userRole == "child"

    val apiBaseUrl: String
        get() = "$serverUrl/api"

    fun clear() {
        prefs.edit().clear().apply()
    }
}
