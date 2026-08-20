package com.trustmesh.app.core.intelligence.context

import com.trustmesh.app.core.events.EventSource
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.SecurityEvent
import com.trustmesh.app.core.identity.CallerIdentity
import com.trustmesh.app.core.identity.IdentitySource
import com.trustmesh.app.core.intelligence.risk.RiskEngineConfig
import com.trustmesh.app.interaction.Interaction
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AttackContextEngineTest {

    private fun createInteraction(
        isUnknownCaller: Boolean,
        notificationTitle: String? = null,
        notificationText: String? = null,
        hasIncomingCallEvidence: Boolean = true
    ): Interaction {
        val identity = if (isUnknownCaller) {
            CallerIdentity(
                phoneNumber = "+1234567890",
                displayName = null,
                source = IdentitySource.UNKNOWN,
                isKnown = false
            )
        } else {
            CallerIdentity(
                phoneNumber = "+1234567890",
                displayName = "John Doe",
                source = IdentitySource.LOCAL_CONTACT,
                isKnown = true
            )
        }
        return Interaction(
            id = UUID.randomUUID().toString(),
            title = "Test Interaction",
            timestamp = "2023-01-01 12:00:00",
            riskLevel = com.trustmesh.app.core.events.RiskLevel.LOW,
            summary = "Summary",
            callerIdentity = identity,
            evidence = if (hasIncomingCallEvidence) listOf("Incoming call") else emptyList(),
            notificationTitle = notificationTitle,
            notificationText = notificationText
        )
    }

    private fun createNotificationEvent(title: String, text: String, timeOffsetMs: Long = 0): SecurityEvent {
        return SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            metadata = mapOf("title" to title, "text" to text),
            timestamp = System.currentTimeMillis() - timeOffsetMs
        )
    }
    
    private fun createCallEvent(identity: String, timeOffsetMs: Long = 0): SecurityEvent {
        return SecurityEvent(
            type = EventType.INCOMING_CALL,
            source = EventSource.CALL_SCREENING_SERVICE,
            identity = identity,
            timestamp = System.currentTimeMillis() - timeOffsetMs
        )
    }

    @Test
    fun testOtpTheft_UnknownCallerAndOtpNotification() {
        val interaction = createInteraction(isUnknownCaller = true)
        val otpEvent = createNotificationEvent("Your OTP Code", "Your verification code is 123456.")
        
        val context = AttackContextEngine.evaluateContext(interaction, listOf(otpEvent))
        
        assertNotNull(context)
        assertEquals(ContextType.OTP_THEFT, context?.contextType)
        assertEquals(InferredIntent.POSSIBLE_OTP_THEFT, context?.inferredIntent)
    }

    @Test
    fun testFinancialFraud_UnknownCallerAndBankNotification() {
        val interaction = createInteraction(isUnknownCaller = true)
        val bankEvent = createNotificationEvent("Bank Alert", "Amount debited from account.")
        
        val context = AttackContextEngine.evaluateContext(interaction, listOf(bankEvent))
        
        assertNotNull(context)
        assertEquals(ContextType.FINANCIAL_FRAUD, context?.contextType)
        assertEquals(InferredIntent.POSSIBLE_FINANCIAL_FRAUD, context?.inferredIntent)
    }

    @Test
    fun testCombined_OtpAndFinancialFraud() {
        val interaction = createInteraction(isUnknownCaller = true)
        val combinedEvent = createNotificationEvent("Bank OTP", "Your bank payment verification code is 123.")
        
        val context = AttackContextEngine.evaluateContext(interaction, listOf(combinedEvent))
        
        assertNotNull(context)
        assertEquals(ContextType.FINANCIAL_FRAUD, context?.contextType)
        assertEquals(InferredIntent.POSSIBLE_FINANCIAL_FRAUD, context?.inferredIntent)
        assertTrue(context!!.detectedPatterns.contains("OTP Notification"))
        assertTrue(context.detectedPatterns.contains("Financial Notification"))
    }

    @Test
    fun testKnownCaller_ShouldReturnNull() {
        val interaction = createInteraction(isUnknownCaller = false)
        val otpEvent = createNotificationEvent("Your OTP Code", "Code 123456.")
        
        val context = AttackContextEngine.evaluateContext(interaction, listOf(otpEvent))
        
        assertNull(context)
    }

    @Test
    fun testEventsOutsideWindow_ShouldReturnNull() {
        val interaction = createInteraction(isUnknownCaller = true)
        val outsideWindowMs = RiskEngineConfig.RELATED_EVENT_WINDOW_MS + 10000 // outside window
        val otpEvent = createNotificationEvent("Your OTP Code", "Code 123456.", timeOffsetMs = outsideWindowMs)
        
        val context = AttackContextEngine.evaluateContext(interaction, listOf(otpEvent))
        
        assertNull(context)
    }

    @Test
    fun testRepeatedCalls_UnknownCaller_ReturnsSocialEngineering() {
        val interaction = createInteraction(isUnknownCaller = true)
        val call1 = createCallEvent("+1234567890", 1000)
        val call2 = createCallEvent("+1234567890", 500)
        
        val context = AttackContextEngine.evaluateContext(interaction, listOf(call1, call2))
        
        assertNotNull(context)
        assertEquals(ContextType.SOCIAL_ENGINEERING, context?.contextType)
        assertEquals(InferredIntent.POSSIBLE_SOCIAL_ENGINEERING, context?.inferredIntent)
    }

    @Test
    fun testOtpInCurrentInteraction_ReturnsOtpTheft() {
        val interaction = createInteraction(
            isUnknownCaller = true, 
            notificationTitle = "OTP Alert", 
            notificationText = "Code is 123456"
        )
        
        val context = AttackContextEngine.evaluateContext(interaction, emptyList())
        
        assertNotNull(context)
        assertEquals(ContextType.OTP_THEFT, context?.contextType)
    }
}
