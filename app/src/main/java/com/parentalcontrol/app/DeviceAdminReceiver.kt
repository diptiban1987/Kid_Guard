package com.parentalcontrol.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.parentalcontrol.app.api.CloudConfig

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        CloudConfig.deviceAdminActive = true
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        CloudConfig.deviceAdminActive = false
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val isAuthenticated = CloudConfig.uninstallPassword.isNotEmpty()
        if (!isAuthenticated) {
            Toast.makeText(context, "Enter uninstall password in app settings first", Toast.LENGTH_LONG).show()
            return "This app is a security service. To disable, open the app and enter your uninstall password."
        }
        return "Enter your uninstall password to disable device admin."
    }
}
