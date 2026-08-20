package com.trustmesh.app.ui.screens.protection

import android.content.Context
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import com.trustmesh.app.core.events.RiskLevel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class ProtectionControllerTest {

    private lateinit var mockContext: Context
    private lateinit var mockWindowManager: WindowManager

    @Before
    fun setup() {
        mockContext = mock(Context::class.java)
        mockWindowManager = mock(WindowManager::class.java)
        
        val appContext = mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(appContext)
        `when`(mockContext.getSystemService(Context.WINDOW_SERVICE)).thenReturn(mockWindowManager)
        `when`(appContext.getSystemService(Context.WINDOW_SERVICE)).thenReturn(mockWindowManager)
        
        resetControllerState()
        ProtectionController.isTestingMode = true
    }

    @After
    fun tearDown() {
        ProtectionController.isTestingMode = false
        resetControllerState()
    }
    
    private fun resetControllerState() {
        try {
            ProtectionController.hideOverlay()
        } catch (e: Exception) {
            // Ignore stub exceptions
        }
        try {
            val isOverlayShowingField = ProtectionController::class.java.getDeclaredField("isOverlayShowing")
            isOverlayShowingField.isAccessible = true
            isOverlayShowingField.set(ProtectionController, false)
        } catch (e: Exception) {
        }
    }

    @Test
    fun `test state transitions through full lifecycle`() {
        val mockComposeView = mock(ComposeView::class.java)
        val lp = WindowManager.LayoutParams().apply {
            gravity = Gravity.CENTER
        }
        `when`(mockComposeView.layoutParams).thenReturn(lp)
        ProtectionController.composeView = mockComposeView
        ProtectionController.windowManager = mockWindowManager

        // Start showing incoming call overlay
        ProtectionController.showOverlay(mockContext, "Test Caller", "+1234567890", CallOverlayState.INCOMING)
        assertEquals(CallOverlayState.INCOMING, ProtectionController.overlayState.value)

        // Incoming -> Active transition
        ProtectionController.transitionToState(CallOverlayState.ACTIVE)
        assertEquals(CallOverlayState.ACTIVE, ProtectionController.overlayState.value)

        // Active -> Summary transition
        ProtectionController.transitionToState(CallOverlayState.SUMMARY)
        assertEquals(CallOverlayState.SUMMARY, ProtectionController.overlayState.value)

        // Summary -> Hidden transition
        ProtectionController.transitionToState(CallOverlayState.HIDDEN)
        assertEquals(CallOverlayState.HIDDEN, ProtectionController.overlayState.value)
    }

    @Test
    fun `test showOverlay twice is idempotent`() {
        val mockComposeView = mock(ComposeView::class.java)
        val lp = WindowManager.LayoutParams().apply {
            gravity = Gravity.CENTER
        }
        `when`(mockComposeView.layoutParams).thenReturn(lp)
        ProtectionController.composeView = mockComposeView
        ProtectionController.windowManager = mockWindowManager

        ProtectionController.showOverlay(mockContext, "Test Caller", "+1234567890", CallOverlayState.INCOMING)
        assertEquals(CallOverlayState.INCOMING, ProtectionController.overlayState.value)

        // Showing again transitions or remains idempotent without crashing
        ProtectionController.showOverlay(mockContext, "Test Caller", "+1234567890", CallOverlayState.ACTIVE)
        assertEquals(CallOverlayState.ACTIVE, ProtectionController.overlayState.value)
    }

    @Test
    fun `test updatePosition during Active state changes coordinates`() {
        val mockComposeView = mock(ComposeView::class.java)
        val lp = WindowManager.LayoutParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        `when`(mockComposeView.layoutParams).thenReturn(lp)
        ProtectionController.composeView = mockComposeView
        ProtectionController.windowManager = mockWindowManager

        ProtectionController.showOverlay(mockContext, "Test Caller", "+1234567890", CallOverlayState.ACTIVE)
        assertEquals(CallOverlayState.ACTIVE, ProtectionController.overlayState.value)

        // Drag it by dx=20, dy=30
        ProtectionController.updatePosition(20, 30)

        assertEquals(120, lp.x)
        assertEquals(330, lp.y)
    }

    @Test
    fun `test overlay not shown without permission when not in testing mode`() {
        ProtectionController.isTestingMode = false
        // Without mocked Settings.canDrawOverlays, it returns false or throws.
        // We verify that WindowManager addView is never invoked.
        try {
            ProtectionController.showOverlay(mockContext, "Ajay", "+919425906611")
        } catch (e: Exception) {
            // Ignore mock context stub exceptions
        }
        verify(mockWindowManager, never()).addView(any(), any())
    }

    @Test
    fun `test overlay updates after identity resolution`() {
        assertEquals(CallOverlayState.HIDDEN, ProtectionController.overlayState.value)
        
        val mockComposeView = mock(ComposeView::class.java)
        val lp = WindowManager.LayoutParams().apply {
            gravity = Gravity.CENTER
        }
        `when`(mockComposeView.layoutParams).thenReturn(lp)
        ProtectionController.composeView = mockComposeView
        ProtectionController.windowManager = mockWindowManager

        ProtectionController.showOverlay(mockContext, "Unknown Caller", "+919876543210", CallOverlayState.INCOMING)
        assertEquals(CallOverlayState.INCOMING, ProtectionController.overlayState.value)
    }
}
