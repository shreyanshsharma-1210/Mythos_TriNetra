package com.trustmesh.app.ui.screens.digitalarrest

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.trustmesh.app.core.alert.EmergencyAlarmManager
import com.trustmesh.app.ui.theme.TrustMeshTheme

private const val TAG = "DigitalArrestOverlay"

/**
 * Manages rendering the Digital Arrest Security Dashboard as a System Window Overlay
 * ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]) directly over active apps
 * (e.g. WhatsApp video call) when [Settings.canDrawOverlays] is granted.
 */
object DigitalArrestOverlayManager {

    @Volatile
    private var overlayView: ComposeView? = null

    @Volatile
    private var windowManager: WindowManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private class OverlayLifecycleOwner : SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry
        override val lifecycle: Lifecycle get() = lifecycleRegistry

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay lifecycle destroy failed", e)
            }
        }
    }

    private var currentLifecycleOwner: OverlayLifecycleOwner? = null
    private val viewModelStore = ViewModelStore()

    fun showOverlay(context: Context) {
        val appContext = context.applicationContext

        // Check draw overlay permission
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)
        if (!canOverlay) {
            Log.w(TAG, "⚠ SYSTEM_ALERT_WINDOW permission not granted — system overlay cannot display over video call")
            return
        }

        mainHandler.post {
            try {
                dismissOverlay(appContext)

                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm

                val view = ComposeView(appContext).apply {
                    setContent {
                        TrustMeshTheme {
                            DigitalArrestScreen(
                                onBack = {
                                    dismissOverlay(appContext)
                                }
                            )
                        }
                    }
                }

                val lifecycleOwner = OverlayLifecycleOwner()
                currentLifecycleOwner = lifecycleOwner
                view.setViewTreeLifecycleOwner(lifecycleOwner)
                view.setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore get() = this@DigitalArrestOverlayManager.viewModelStore
                })
                view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            @Suppress("DEPRECATION")
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                wm.addView(view, layoutParams)
                overlayView = view
                Log.i(TAG, "✅ Digital Arrest System Overlay added successfully over active screen / call")

                // Start emergency alarm sound & vibration
                EmergencyAlarmManager.startEmergencyAlarm(appContext)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to add Digital Arrest Overlay: ${e.message}", e)
            }
        }
    }

    fun dismissOverlay(context: Context? = null) {
        mainHandler.post {
            try {
                overlayView?.let { view ->
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view: ${e.message}", e)
            } finally {
                overlayView = null
                currentLifecycleOwner?.destroy()
                currentLifecycleOwner = null
            }
            if (context != null) {
                EmergencyAlarmManager.stopEmergencyAlarm(context)
            }
        }
    }
}
