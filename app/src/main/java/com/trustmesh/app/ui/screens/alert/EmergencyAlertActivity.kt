package com.trustmesh.app.ui.screens.alert

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.trustmesh.app.core.alert.EmergencyAlarmManager
import com.trustmesh.app.ui.theme.TrustMeshTheme

private const val TAG = "EmergencyAlertActivity"

/**
 * Fullscreen / High-Priority Emergency Activity displayed when a "TriNetra" emergency SMS is received.
 * Wakes up the screen, shows over keyguard/lockscreen, and displays the emergency message box.
 */
class EmergencyAlertActivity : ComponentActivity() {

    private var sender: String = ""
    private var messageText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sender = intent.getStringExtra(EXTRA_SENDER) ?: "Unknown"
        messageText = intent.getStringExtra(EXTRA_MESSAGE) ?: "Emergency TriNetra alert received."

        Log.i(TAG, "EmergencyAlertActivity created — sender: $sender, message: ${messageText.take(30)}...")

        setupWindowFlags()

        setContent {
            TrustMeshTheme {
                EmergencyAlertScreen(
                    sender = sender,
                    messageText = messageText,
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }

    private fun setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "EmergencyAlertActivity destroyed")
    }

    companion object {
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_MESSAGE = "extra_message"

        fun launch(context: Context, sender: String, messageText: String) {
            try {
                val intent = Intent(context, EmergencyAlertActivity::class.java).apply {
                    putExtra(EXTRA_SENDER, sender)
                    putExtra(EXTRA_MESSAGE, messageText)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(intent)
                Log.i(TAG, "EmergencyAlertActivity launched successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch EmergencyAlertActivity: ${e.message}", e)
            }
        }
    }
}
