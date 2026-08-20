package com.trustmesh.app.ui.screens.protection

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.firewall.OverlayPermissionHelper
import com.trustmesh.app.core.incident.IncidentStatus
import com.trustmesh.app.core.incident.SecurityIncidentManager
import com.trustmesh.app.interaction.InteractionManager
import com.trustmesh.app.ui.theme.TrustMeshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "TrustMeshOverlay"

enum class CallOverlayState {
    INCOMING,
    ACTIVE,
    SUMMARY,
    HIDDEN
}

object ProtectionController {

    var isTestingMode = false

    internal var windowManager: WindowManager? = null
    internal var composeView: ComposeView? = null

    @Volatile
    private var isOverlayShowing = false

    private val _overlayState = MutableStateFlow(CallOverlayState.HIDDEN)
    val overlayState: StateFlow<CallOverlayState> = _overlayState.asStateFlow()

    private var activeX = 100
    private var activeY = 300
    private var activeCallStartTimeMs = 0L
    private var activeCallDurationSeconds = 0L
    var summaryDismissDelayMs = 5000L

    private val dismissHandler by lazy { Handler(Looper.getMainLooper()) }
    private val dismissRunnable = Runnable {
        if (_overlayState.value == CallOverlayState.SUMMARY) {
            transitionToStateInternal(CallOverlayState.HIDDEN)
        }
    }

    private class MyLifecycleOwner : SavedStateRegistryOwner {
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
                Log.w(TAG, "Lifecycle destroy failed — non-fatal", e)
            }
        }
    }

    private var currentLifecycleOwner: MyLifecycleOwner? = null
    private val viewModelStore = ViewModelStore()

    // ── Public API ─────────────────────────────────────────────────────────────

    fun showOverlay(context: Context, callerName: String, callerNumber: String) {
        showOverlay(context, callerName, callerNumber, CallOverlayState.INCOMING)
    }

    fun showOverlay(context: Context, callerName: String, callerNumber: String, initialState: CallOverlayState) {
        enforceMainThread {
            if (isOverlayShowing) {
                Log.d(TAG, "showOverlay called while already showing — transitioning to state=$initialState")
                transitionToStateInternal(initialState)
            } else {
                showOverlayInternal(context, callerName, callerNumber, initialState)
            }
        }
    }

    fun transitionToState(state: CallOverlayState) {
        enforceMainThread {
            transitionToStateInternal(state)
        }
    }

    fun hideOverlay(reason: String = "unknown") {
        enforceMainThread { hideOverlayInternal(reason) }
    }

    fun updatePosition(dx: Int, dy: Int) {
        enforceMainThread {
            val view = composeView ?: return@enforceMainThread
            val wm = windowManager ?: return@enforceMainThread
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@enforceMainThread
            if (_overlayState.value == CallOverlayState.ACTIVE && lp.gravity == (Gravity.TOP or Gravity.START)) {
                activeX += dx
                activeY += dy
                lp.x = activeX
                lp.y = activeY
                try {
                    if (!isTestingMode) {
                        wm.updateViewLayout(view, lp)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update layout params on drag", e)
                }
            }
        }
    }

    fun getActiveCallDurationSeconds(): Long = activeCallDurationSeconds

    // ── Internal implementation (must run on main thread) ──────────────────────

    private fun showOverlayInternal(context: Context, callerName: String, callerNumber: String, initialState: CallOverlayState) {
        if (isOverlayShowing) {
            Log.d(TAG, "showOverlayInternal called while already showing — idempotent, skipping")
            return
        }

        if (isTestingMode) {
            isOverlayShowing = true
            _overlayState.value = initialState
            if (initialState == CallOverlayState.ACTIVE) {
                activeCallStartTimeMs = System.currentTimeMillis()
            }
            return
        }

        if (!OverlayPermissionHelper.hasOverlayPermission(context)) {
            Log.w(TAG, "Overlay permission missing — cannot show overlay")
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "canDrawOverlays returned false — skipping (OEM restriction)")
            return
        }

        Log.i(TAG, "Showing overlay for caller in state=$initialState")

        val appContext = context.applicationContext

        try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                Log.e(TAG, "WindowManager unavailable — cannot show overlay")
                return
            }
            windowManager = wm

            val view = ComposeView(appContext).apply {
                setContent {
                    val state by overlayState.collectAsState()
                    val interactions by InteractionManager.interactions.collectAsState()
                    val incidents by SecurityIncidentManager.incidents.collectAsState()
                    val activeIncident = incidents.firstOrNull { it.status == IncidentStatus.ACTIVE }

                    val currentInteraction = interactions.firstOrNull()
                    val riskLevel = activeIncident?.severity ?: (currentInteraction?.riskLevel ?: RiskLevel.LOW)
                    val callerIdentity = currentInteraction?.callerIdentity
                    val callerReputation = currentInteraction?.callerReputation

                    LaunchedEffect(state, riskLevel) {
                        updateWindowLayoutParams(state, riskLevel)
                    }

                    TrustMeshTheme {
                        ProtectionOverlay(
                            state = state,
                            callerIdentity = callerIdentity,
                            callerReputation = callerReputation,
                            fallbackName = callerName,
                            fallbackNumber = callerNumber,
                            riskLevel = riskLevel,
                            riskAssessment = currentInteraction?.riskAssessment,
                            activeIncident = activeIncident,
                            activeCallDurationSeconds = activeCallDurationSeconds,
                            onDismiss = { hideOverlay("user_dismissed") }
                        )
                    }
                }
            }
            composeView = view

            val lifecycleOwner = MyLifecycleOwner()
            currentLifecycleOwner = lifecycleOwner
            view.setViewTreeLifecycleOwner(lifecycleOwner)
            view.setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore get() = this@ProtectionController.viewModelStore
            })
            view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).also {
                it.gravity = Gravity.CENTER
            }

            wm.addView(view, layoutParams)
            isOverlayShowing = true
            _overlayState.value = initialState

            if (initialState == CallOverlayState.ACTIVE) {
                activeCallStartTimeMs = System.currentTimeMillis()
            }

            Log.i(TAG, "Overlay added to WindowManager successfully")

        } catch (e: Exception) {
            Log.e(TAG, "WindowManager.addView() failed — cleaning up", e)
            cleanUpState()
        }
    }

    private fun transitionToStateInternal(state: CallOverlayState) {
        Log.i(TAG, "Transitioning overlay to state=$state")
        if (state == CallOverlayState.HIDDEN) {
            hideOverlayInternal("transition_to_hidden")
            return
        }

        val view = composeView
        val wm = windowManager
        if (view == null || wm == null) {
            Log.w(TAG, "Cannot transition state — view or windowManager is null")
            return
        }

        _overlayState.value = state

        if (state == CallOverlayState.ACTIVE) {
            activeCallStartTimeMs = System.currentTimeMillis()
        }

        if (state == CallOverlayState.SUMMARY) {
            if (activeCallStartTimeMs > 0) {
                activeCallDurationSeconds = (System.currentTimeMillis() - activeCallStartTimeMs) / 1000
                activeCallStartTimeMs = 0L
            }
            if (!isTestingMode) {
                dismissHandler.removeCallbacks(dismissRunnable)
                dismissHandler.postDelayed(dismissRunnable, summaryDismissDelayMs)
            }
        }

        if (isTestingMode) {
            updateWindowLayoutParams(state, RiskLevel.LOW)
        }
    }

    private fun hideOverlayInternal(reason: String) {
        Log.i(TAG, "Hiding overlay — reason=$reason")

        if (!isOverlayShowing && composeView == null) {
            Log.d(TAG, "hideOverlay called when not showing — idempotent, skipping")
            return
        }

        try {
            val view = composeView
            val wm = windowManager
            if (view != null && wm != null) {
                if (!isTestingMode) {
                    wm.removeView(view)
                }
                Log.d(TAG, "WindowManager.removeView() successful")
            } else {
                Log.w(TAG, "removeView skipped — view=$view wm=$wm")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WindowManager.removeView() failed — non-fatal", e)
        } finally {
            cleanUpState()
        }
    }

    private fun cleanUpState() {
        try { currentLifecycleOwner?.destroy() } catch (_: Exception) {}
        currentLifecycleOwner = null
        composeView = null
        windowManager = null
        isOverlayShowing = false
        _overlayState.value = CallOverlayState.HIDDEN
        if (!isTestingMode) {
            dismissHandler.removeCallbacks(dismissRunnable)
        }
        activeCallStartTimeMs = 0L
        activeX = 100
        activeY = 300
        Log.d(TAG, "Overlay state cleaned up")
    }

    private fun updateWindowLayoutParams(state: CallOverlayState, riskLevel: RiskLevel) {
        val view = composeView ?: return
        val wm = windowManager ?: return
        try {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return

            when (state) {
                CallOverlayState.INCOMING -> {
                    if (riskLevel == RiskLevel.CRITICAL) {
                        lp.width = WindowManager.LayoutParams.MATCH_PARENT
                        lp.height = WindowManager.LayoutParams.MATCH_PARENT
                        lp.gravity = Gravity.CENTER
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
                    } else {
                        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                        lp.gravity = Gravity.CENTER
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    }
                }
                CallOverlayState.ACTIVE -> {
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = activeX
                    lp.y = activeY
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                }
                CallOverlayState.SUMMARY -> {
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.gravity = Gravity.CENTER
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                }
                CallOverlayState.HIDDEN -> {
                    // removed
                }
            }

            if (!isTestingMode) {
                wm.updateViewLayout(view, lp)
            }
            Log.d(TAG, "WindowParams updated for state=$state riskLevel=$riskLevel")
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout failed — non-fatal", e)
        }
    }

    // ── Thread safety helper ──────────────────────────────────────────────────

    private fun enforceMainThread(block: () -> Unit) {
        if (isTestingMode || Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
        }
    }
}
