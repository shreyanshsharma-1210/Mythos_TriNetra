package com.trustmesh.app.core.intelligence.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberExtractorTest {

    @Test
    fun test10DigitIndianNumber() {
        val candidates = PhoneNumberExtractor.extract("Call me at 9876543210 immediately.")
        assertEquals(1, candidates.size)
        assertEquals("+919876543210", candidates[0])
    }

    @Test
    fun testPlus91Number() {
        val candidates = PhoneNumberExtractor.extract("Please contact +919876543210 for support.")
        assertEquals(1, candidates.size)
        assertEquals("+919876543210", candidates[0])
    }

    @Test
    fun test91PrefixedNumber() {
        val candidates = PhoneNumberExtractor.extract("Reach us: 919876543210")
        assertEquals(1, candidates.size)
        assertEquals("+919876543210", candidates[0])
    }

    @Test
    fun testSpacedNumber() {
        val candidates = PhoneNumberExtractor.extract("Call +91 98765 43210")
        assertEquals(1, candidates.size)
        assertEquals("+919876543210", candidates[0])
    }

    @Test
    fun testHyphenatedNumber() {
        val candidates = PhoneNumberExtractor.extract("Call 987-654-3210")
        assertEquals(1, candidates.size)
        assertEquals("+919876543210", candidates[0])
    }

    @Test
    fun testOtpMustNotMatch() {
        val candidates = PhoneNumberExtractor.extract("Your OTP is 123456")
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun test4DigitNumberMustNotMatch() {
        val candidates = PhoneNumberExtractor.extract("Pin: 4567")
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun testTransactionReferenceMustNotMatch() {
        val candidates = PhoneNumberExtractor.extract("Txn ID 9876543210123 completed")
        assertTrue(candidates.isEmpty()) // Too long
    }

    @Test
    fun testEmptyText() {
        val candidates = PhoneNumberExtractor.extract("")
        assertTrue(candidates.isEmpty())
    }
}
