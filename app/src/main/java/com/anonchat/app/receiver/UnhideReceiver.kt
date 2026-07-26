package com.anonchat.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.anonchat.app.ui.main.MainActivity
import com.anonchat.app.util.AppHider
import com.anonchat.app.util.SecretCodeManager

class UnhideReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppHider.UNHIDE_ACTION) {
            val isHidden = AppHider.isAppHidden(context)
            if (isHidden) {
                AppHider.showApp(context)
                Toast.makeText(context, "AnonChat is now visible!", Toast.LENGTH_LONG).show()

                // Try to launch the app
                try {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("unhide", true)
                    }
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    // If MainActivity can't be launched, alias is stillenabled
                    Toast.makeText(context, "App unhidden. Please find it in your app drawer.", Toast.LENGTH_LONG).show()
                }
            } else {
                // App already visible, just open it
                try {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    // Fallback
                }
            }
        }
    }
}
