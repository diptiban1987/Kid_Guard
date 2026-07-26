package com.anonchat.app.util

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import com.anonchat.app.receiver.SecretCodeReceiver

object SecretCodeReceiverManager {

    private var receiver: SecretCodeReceiver? = null

    fun registerDynamicReceiver(context: Context) {
        val code = SecretCodeManager.getSecretCode(context) ?: return

        try {
            unregisterDynamicReceiver(context)
        } catch (_: Exception) { }

        receiver = SecretCodeReceiver()
        val filter = IntentFilter("android.provider.Telephony.SECRET_CODE")
        filter.addDataScheme("android_secret_code")
        filter.addDataAuthority(code, null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregisterDynamicReceiver(context: Context) {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) { }
            receiver = null
        }
    }

    fun updateSecretCodeInManifest(context: Context, newCode: String) {
        val oldCode = SecretCodeManager.getSecretCode(context)

        try {
            unregisterDynamicReceiver(context)
        } catch (_: Exception) { }

        val componentName = ComponentName(
            context,
            "com.anonchat.app.receiver.SecretCodeReceiver"
        )

        context.packageManager.setComponentEnabledSetting(
            componentName,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )

        context.packageManager.setComponentEnabledSetting(
            componentName,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )

        registerDynamicReceiver(context)
    }
}
