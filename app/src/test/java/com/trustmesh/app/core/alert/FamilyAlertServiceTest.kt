package com.trustmesh.app.core.alert

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fake implementation of TextBeeHttpClient for robust unit testing without network calls.
 */
class FakeTextBeeHttpClient(
    var shouldSucceed: Boolean = true,
    var errorToThrow: Exception? = null
) : TextBeeHttpClient {
    var callCount = 0
    var lastApiKey: String? = null
    var lastDeviceId: String? = null
    var lastRecipients: List<String>? = null
    var lastMessage: String? = null

    override suspend fun sendSms(
        apiKey: String,
        deviceId: String,
        recipients: List<String>,
        message: String,
        simSlot: Int?
    ): Result<Unit> {
        callCount++
        lastApiKey = apiKey
        lastDeviceId = deviceId
        lastRecipients = recipients
        lastMessage = message

        errorToThrow?.let { throw it }

        return if (shouldSucceed) {
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("Fake API network error"))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FamilyAlertServiceTest {

    private lateinit var fakeHttpClient: FakeTextBeeHttpClient

    @Before
    fun setup() {
        FamilyAlertService.clearForTesting()
        fakeHttpClient = FakeTextBeeHttpClient()
    }

    @After
    fun tearDown() {
        FamilyAlertService.clearForTesting()
    }

    @Test
    fun `riskScore 49 does NOT send SMS`() = runBlocking {
        val result = FamilyAlertService.sendHighRiskAlert(
            riskScore = 49,
            interactionId = "int_49",
            httpClient = fakeHttpClient
        )
        assertFalse("49 risk score should return false", result)
        assertEquals("HTTP client should not be called for score 49", 0, fakeHttpClient.callCount)
    }

    @Test
    fun `riskScore 50 does NOT send SMS`() = runBlocking {
        val result = FamilyAlertService.sendHighRiskAlert(
            riskScore = 50,
            interactionId = "int_50",
            httpClient = fakeHttpClient
        )
        assertFalse("50 risk score should return false", result)
        assertEquals("HTTP client should not be called for score 50", 0, fakeHttpClient.callCount)
    }

    @Test
    fun `riskScore 51 DOES send SMS`() = runBlocking {
        val result = FamilyAlertService.sendHighRiskAlert(
            riskScore = 51,
            interactionId = "int_51",
            httpClient = fakeHttpClient
        )
        assertTrue("51 risk score should return true on successful SMS dispatch", result)
        assertEquals("HTTP client should be called once for score 51", 1, fakeHttpClient.callCount)
        assertNotNull(fakeHttpClient.lastMessage)
        assertTrue(
            "Message must contain dynamic risk score 51%",
            fakeHttpClient.lastMessage!!.contains("51%")
        )
    }

    @Test
    fun `riskScore 95 DOES send SMS`() = runBlocking {
        val result = FamilyAlertService.sendHighRiskAlert(
            riskScore = 95,
            interactionId = "int_95",
            httpClient = fakeHttpClient
        )
        assertTrue("95 risk score should return true on successful SMS dispatch", result)
        assertEquals("HTTP client should be called once for score 95", 1, fakeHttpClient.callCount)
        assertNotNull(fakeHttpClient.lastMessage)
        assertTrue(
            "Message must contain dynamic risk score 95%",
            fakeHttpClient.lastMessage!!.contains("Risk Score: 95%")
        )
    }

    @Test
    fun `riskScore 100 DOES send SMS`() = runBlocking {
        val result = FamilyAlertService.sendHighRiskAlert(
            riskScore = 100,
            interactionId = "int_100",
            httpClient = fakeHttpClient
        )
        assertTrue("100 risk score should return true on successful SMS dispatch", result)
        assertEquals("HTTP client should be called once for score 100", 1, fakeHttpClient.callCount)
        assertNotNull(fakeHttpClient.lastMessage)
        assertTrue(
            "Message must contain dynamic risk score 100%",
            fakeHttpClient.lastMessage!!.contains("Risk Score: 100%")
        )
    }

    @Test
    fun `SMS API failure handles error safely without throwing or crashing`() = runBlocking {
        fakeHttpClient.shouldSucceed = false
        val result = FamilyAlertService.sendHighRiskAlert(
            riskScore = 92,
            interactionId = "int_fail",
            httpClient = fakeHttpClient
        )
        assertFalse("API failure should return false safely", result)
        assertEquals("HTTP client should have been attempted once", 1, fakeHttpClient.callCount)
    }

    @Test
    fun `SMS API network exception handled safely without crashing`() = runBlocking {
        fakeHttpClient.errorToThrow = RuntimeException("Connection timeout")
        val result = FamilyAlertService.sendHighRiskAlert(
            riskScore = 92,
            interactionId = "int_exception",
            httpClient = fakeHttpClient
        )
        assertFalse("Exception should be caught and return false safely", result)
        assertEquals("HTTP client should have been attempted once", 1, fakeHttpClient.callCount)
    }

    @Test
    fun `risk recalculation prevents duplicate SMS for same interactionId`() = runBlocking {
        val firstResult = FamilyAlertService.sendHighRiskAlert(
            riskScore = 88,
            interactionId = "int_dup_123",
            httpClient = fakeHttpClient
        )
        assertTrue("First evaluation should send SMS", firstResult)
        assertEquals("HTTP client count should be 1", 1, fakeHttpClient.callCount)

        val secondResult = FamilyAlertService.sendHighRiskAlert(
            riskScore = 95,
            interactionId = "int_dup_123",
            httpClient = fakeHttpClient
        )
        assertFalse("Second evaluation for same interactionId should be blocked as duplicate", secondResult)
        assertEquals("HTTP client count must remain 1 after duplicate attempt", 1, fakeHttpClient.callCount)
    }

    @Test
    fun `multiple family recipients are correctly passed to SMS client`() = runBlocking {
        FamilyAlertService.sendHighRiskAlert(
            riskScore = 89,
            interactionId = "int_multi_recipients",
            httpClient = fakeHttpClient
        )
        assertNotNull(fakeHttpClient.lastRecipients)
        val recipients = fakeHttpClient.lastRecipients!!
        val expectedRecipients = FamilyAlertConfig.getFamilyNumbers()
        assertEquals("Recipients list size must match configured family numbers count", expectedRecipients.size, recipients.size)
        assertTrue("Recipients list should equal configured family numbers", recipients.containsAll(expectedRecipients))
    }

    @Test
    fun `API key is not contained in message content and phone numbers are masked in logs`() = runBlocking {
        FamilyAlertService.sendHighRiskAlert(
            riskScore = 90,
            interactionId = "int_key_check",
            httpClient = fakeHttpClient
        )
        val msg = fakeHttpClient.lastMessage ?: ""
        assertFalse("Message body must not reveal API key", msg.contains("txb_"))

        val masked = FamilyAlertService.maskPhoneNumber("+919691600998")
        assertFalse("Masked phone number must mask central digits", masked.contains("969160"))
        assertTrue("Masked phone number should contain asterisks", masked.contains("*"))
    }
}
