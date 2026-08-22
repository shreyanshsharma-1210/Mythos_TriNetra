package com.trustmesh.app.ui.screens.digitalarrest

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

private const val TAG = "DigitalArrestActivity"

/**
 * Fullscreen high-priority Activity displayed when the "2000" Digital Arrest SMS trigger is received.
 * Wakes up screen, shows over keyguard/lockscreen, triggers alarm audio/vibration,
 * and displays the animated workflow progress and critical dashboard.
 */
class DigitalArrestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "🚨 DigitalArrestActivity created — waking screen and starting emergency alarm")

        setupWindowFlags()

        // Start emergency sound & vibration warning
        EmergencyAlarmManager.startEmergencyAlarm(this)

        setContent {
            TrustMeshTheme {
                DigitalArrestScreen(
                    onBack = {
                        EmergencyAlarmManager.stopEmergencyAlarm(this)
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
        EmergencyAlarmManager.stopEmergencyAlarm(this)
        Log.d(TAG, "DigitalArrestActivity destroyed — alarm stopped")
    }

    companion object {
        fun launch(context: Context) {
            try {
                val intent = Intent(context, DigitalArrestActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(intent)
                Log.i(TAG, "✅ DigitalArrestActivity launched successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch DigitalArrestActivity: ${e.message}", e)
            }
        }
    }
}
