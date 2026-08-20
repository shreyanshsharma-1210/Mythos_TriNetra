package com.trustmesh.app.core.identity

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class LocalContactIdentityResolverTest {

    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver
    private lateinit var mockCursor: Cursor

    @Before
    fun setup() {
        mockContext = mock(Context::class.java)
        mockContentResolver = mock(ContentResolver::class.java)
        mockCursor = mock(Cursor::class.java)
        
        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
    }

    @Test
    fun testKnownLocalContactResolved() {
        runBlocking {
            val resolver = LocalContactIdentityResolver(mockContext)
            resolver.hasContactsPermissionForTesting = true
                
            // Mock query returning a contact using simple any() and isNull() matchers
            `when`(mockContentResolver.query(
                any(),
                any(),
                isNull(),
                isNull(),
                isNull()
            )).thenReturn(mockCursor)
                
            `when`(mockCursor.moveToFirst()).thenReturn(true)
            `when`(mockCursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)).thenReturn(0)
            `when`(mockCursor.getString(0)).thenReturn("Ajay Sharma")

            val result = resolver.resolve("+919876543210")
            
            assertTrue(result.identity.isKnown)
            assertEquals("Ajay Sharma", result.identity.displayName)
            assertEquals(IdentitySource.LOCAL_CONTACT, result.identity.source)
        }
    }

    @Test
    fun testUnknownNumberReturnsUnknownCaller() {
        runBlocking {
            val resolver = LocalContactIdentityResolver(mockContext)
            resolver.hasContactsPermissionForTesting = true
                
            // Mock query returning empty cursor
            `when`(mockContentResolver.query(
                any(),
                any(),
                isNull(),
                isNull(),
                isNull()
            )).thenReturn(mockCursor)
                
            `when`(mockCursor.moveToFirst()).thenReturn(false)

            val result = resolver.resolve("+919876543210")
            
            assertFalse(result.identity.isKnown)
            assertNull(result.identity.displayName)
            assertEquals(IdentitySource.UNKNOWN, result.identity.source)
        }
    }

    @Test
    fun testContactsPermissionDeniedReturnsGracefulFallback() {
        runBlocking {
            val resolver = LocalContactIdentityResolver(mockContext)
            resolver.hasContactsPermissionForTesting = false

            val result = resolver.resolve("+919876543210")
            
            assertFalse(result.identity.isKnown)
            assertNull(result.identity.displayName)
            assertEquals(IdentitySource.UNKNOWN, result.identity.source)
            
            // Verify no query is executed on resolver
            verify(mockContentResolver, never()).query(
                any(),
                any(),
                isNull(),
                isNull(),
                isNull()
            )
        }
    }

    @Test
    fun testDifferentPhoneNumberFormattingMatching() {
        runBlocking {
            // Test normalization matching
            // e.g. "9876543210" normalized is "+919876543210"
            val raw = "9876543210"
            val normalized = PhoneNumberNormalizer.normalize(raw)
            assertEquals("+919876543210", normalized)
            
            val local1 = "09876543210"
            assertEquals("+919876543210", PhoneNumberNormalizer.normalize(local1))
            
            val local2 = "919876543210"
            assertEquals("+919876543210", PhoneNumberNormalizer.normalize(local2))
        }
    }
}
