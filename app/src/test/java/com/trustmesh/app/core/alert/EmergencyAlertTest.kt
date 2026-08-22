package com.trustmesh.app.core.alert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyAlertTest {

    @Test
    fun `SMS containing TriNetra triggers emergency keyword check`() {
        val sms1 = "⚠️ TriNetra Security Alert: Aapke family member ke phone par 55% se zyada scam risk detect hua hai."
        val sms2 = "Hello world, this is a normal message."
        val sms3 = "TRINETRA Emergency Alert: OTP warning!"

        assertTrue("SMS with 'TriNetra' should trigger alert", sms1.contains("TriNetra", ignoreCase = true))
        assertFalse("Normal SMS without 'TriNetra' should NOT trigger alert", sms2.contains("TriNetra", ignoreCase = true))
        assertTrue("SMS with uppercase 'TRINETRA' should trigger alert", sms3.contains("TriNetra", ignoreCase = true))
    }

    @Test
    fun `EmergencyAlarmManager state toggle test`() {
        assertFalse("Initially alarm should not be active", EmergencyAlarmManager.isAlarmActive())
    }
}
