package com.trustmesh.app.interaction

import com.trustmesh.app.core.events.EventSource
import com.trustmesh.app.core.events.EventType
import com.trustmesh.app.core.events.RiskLevel
import com.trustmesh.app.core.events.SecurityEvent
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.anyString
import kotlinx.coroutines.runBlocking

class InteractionManagerTest {

    @Before
    fun setup() {
        InteractionManager.clearForTesting() // We need to add this method or clear the Flow manually
    }

    @Test
    fun `test new notification creates interaction`() {
        val event = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 1000L,
            identity = "com.example.app",
            metadata = mapOf(
                "packageName" to "com.example.app",
                "appName" to "Example App",
                "notificationKey" to "keyA",
                "title" to "Title A",
                "text" to "Text A"
            ),
            initialRisk = RiskLevel.LOW
        )

        InteractionManager.processEvent(event)

        val interactions = InteractionManager.interactions.value
        assertEquals(1, interactions.size)
        assertEquals("keyA", interactions[0].associatedKey)
        assertEquals("Example App", interactions[0].title)
        assertEquals("Title A", interactions[0].notificationTitle)
        assertEquals("Text A", interactions[0].notificationText)
        assertEquals("Example App", interactions[0].appName)
    }

    @Test
    fun `test duplicate notification updates interaction`() {
        val event1 = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 1000L,
            identity = "com.google.android.dialer",
            metadata = mapOf(
                "packageName" to "com.google.android.dialer",
                "appName" to "Google Dialer",
                "notificationKey" to "dialerKey",
                "title" to "Incoming call",
                "text" to ""
            ),
            initialRisk = RiskLevel.LOW
        )

        val event2 = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 2000L,
            identity = "com.google.android.dialer",
            metadata = mapOf(
                "packageName" to "com.google.android.dialer",
                "appName" to "Google Dialer",
                "notificationKey" to "dialerKey",
                "title" to "Call in progress",
                "text" to "+91XXXXXXXXXX"
            ),
            initialRisk = RiskLevel.LOW
        )

        InteractionManager.processEvent(event1)
        InteractionManager.processEvent(event2)

        val interactions = InteractionManager.interactions.value
        assertEquals(1, interactions.size)
        assertEquals("dialerKey", interactions[0].associatedKey)
        assertEquals("Call in progress", interactions[0].notificationTitle)
        assertEquals("+91XXXXXXXXXX", interactions[0].notificationText)
        
        // 1 initial + 1 updated
        assertEquals(2, interactions[0].timeline.size)
        assertTrue(interactions[0].timeline[1].contains("Notification content updated"))
    }

    @Test
    fun `test different notifications create multiple interactions`() {
        val event1 = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 1000L,
            identity = "app.A",
            metadata = mapOf(
                "packageName" to "app.A",
                "notificationKey" to "keyA",
                "title" to "Title A"
            ),
            initialRisk = RiskLevel.LOW
        )

        val event2 = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 2000L,
            identity = "app.B",
            metadata = mapOf(
                "packageName" to "app.B",
                "notificationKey" to "keyB",
                "title" to "Title B"
            ),
            initialRisk = RiskLevel.LOW
        )

        InteractionManager.processEvent(event1)
        InteractionManager.processEvent(event2)

        val interactions = InteractionManager.interactions.value
        assertEquals(2, interactions.size)
    }

    @Test
    fun `test incoming call event is not deduplicated against notifications`() {
        val callEvent = SecurityEvent(
            type = EventType.INCOMING_CALL,
            source = EventSource.CALL_SCREENING_SERVICE,
            timestamp = 1000L,
            identity = "+910000000000",
            metadata = mapOf("handle" to "+910000000000"),
            initialRisk = RiskLevel.LOW
        )

        val notifEvent = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 2000L,
            identity = "com.google.android.dialer",
            metadata = mapOf(
                "packageName" to "com.google.android.dialer",
                "appName" to "Google Dialer",
                "notificationKey" to "dialerKey",
                "title" to "Incoming call"
            ),
            initialRisk = RiskLevel.LOW
        )

        InteractionManager.processEvent(callEvent)
        InteractionManager.processEvent(notifEvent)

        val interactions = InteractionManager.interactions.value
        assertEquals(2, interactions.size)
        
        val titles = interactions.map { it.title }
        assertTrue(titles.contains("+910000000000"))
        assertTrue(titles.contains("Google Dialer"))
    }
    
    @Test
    fun `test notification with empty title and text`() {
        val event = SecurityEvent(
            type = EventType.NOTIFICATION_POSTED,
            source = EventSource.NOTIFICATION_LISTENER_SERVICE,
            timestamp = 1000L,
            identity = "app.C",
            metadata = mapOf(
                "packageName" to "app.C",
                "appName" to "App C",
                "notificationKey" to "keyC"
            ),
            initialRisk = RiskLevel.LOW
        )
        
        InteractionManager.processEvent(event)
        val interactions = InteractionManager.interactions.value
        assertEquals(1, interactions.size)
        assertEquals("", interactions[0].notificationTitle)
        assertEquals("", interactions[0].notificationText)
    }

    @Test
    fun `test async identity resolution updates existing Interaction`() {
        val resolver = mock(com.trustmesh.app.core.identity.CompositeCallerIdentityResolver::class.java)
        val expectedCaller = com.trustmesh.app.core.identity.ResolvedCaller(
            identity = com.trustmesh.app.core.identity.CallerIdentity(
                phoneNumber = "+919876543210",
                displayName = "Ajay Sharma",
                identityType = com.trustmesh.app.core.identity.IdentityType.PERSON,
                confidence = com.trustmesh.app.core.identity.Confidence.HIGH,
                source = com.trustmesh.app.core.identity.IdentitySource.LOCAL_CONTACT,
                isKnown = true
            )
        )
        
        runBlocking {
            `when`(resolver.resolve(anyString())).thenReturn(expectedCaller)
        }
        
        InteractionManager.identityResolver = resolver
        
        val event = SecurityEvent(
            type = EventType.INCOMING_CALL,
            source = EventSource.CALL_SCREENING_SERVICE,
            timestamp = 1000L,
            identity = "+919876543210",
            metadata = mapOf("handle" to "+919876543210"),
            initialRisk = RiskLevel.LOW
        )
        
        InteractionManager.processEvent(event)
        
        // Wait up to 1 second for async resolution on Dispatchers.IO to finish
        var found = false
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 1000) {
            val interactions = InteractionManager.interactions.value
            if (interactions.isNotEmpty() && interactions[0].callerIdentity != null) {
                found = true
                break
            }
            Thread.sleep(50)
        }
        
        assertTrue("Interaction must be updated with CallerIdentity asynchronously", found)
        val interaction = InteractionManager.interactions.value[0]
        assertEquals("Ajay Sharma", interaction.title)
        assertEquals("Ajay Sharma", interaction.callerIdentity?.displayName)
        assertEquals(com.trustmesh.app.core.identity.IdentitySource.LOCAL_CONTACT, interaction.callerIdentity?.source)
    }
}
