package com.trustmesh.app.core.events

import android.net.Uri
import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class EventNormalizerTest {

    @Test
    fun testNormalizeIncomingCallWithCallerName() {
        val mockDetails = mock(Call.Details::class.java)
        
        val uri = mock(Uri::class.java)
        `when`(uri.schemeSpecificPart).thenReturn("+1234567890")
        `when`(mockDetails.handle).thenReturn(uri)
        `when`(mockDetails.creationTimeMillis).thenReturn(1000L)
        `when`(mockDetails.callDirection).thenReturn(Call.Details.DIRECTION_INCOMING)
        `when`(mockDetails.callerDisplayName).thenReturn("Test User")

        val event = EventNormalizer.normalizeIncomingCall(mockDetails)

        assertEquals(EventType.INCOMING_CALL, event.type)
        assertEquals(EventSource.CALL_SCREENING_SERVICE, event.source)
        assertEquals(1000L, event.timestamp)
        assertEquals("+1234567890", event.identity)
        assertEquals("true", event.metadata["isIncoming"])
        assertEquals("+1234567890", event.metadata["callerNumber"])
        // OEM display name is stored separately under oemDisplayName per Phase 6 privacy design.
        // TrustMesh does NOT propagate the OEM-provided name as authoritative caller identity.
        assertEquals("Test User", event.metadata["oemDisplayName"])
    }

    @Test
    fun testNormalizeIncomingCallUnknownCallerFallback() {
        val mockDetails = mock(Call.Details::class.java)
        
        `when`(mockDetails.handle).thenReturn(null) // No handle
        `when`(mockDetails.creationTimeMillis).thenReturn(2000L)
        `when`(mockDetails.callDirection).thenReturn(Call.Details.DIRECTION_INCOMING)
        `when`(mockDetails.callerDisplayName).thenReturn(null) // No caller name

        val event = EventNormalizer.normalizeIncomingCall(mockDetails)

        assertEquals(EventType.INCOMING_CALL, event.type)
        assertEquals("Unknown", event.identity)
        assertEquals("Unknown", event.metadata["callerNumber"])
        assertNull(event.metadata["callerName"]) // Should not be added to metadata
    }
}
