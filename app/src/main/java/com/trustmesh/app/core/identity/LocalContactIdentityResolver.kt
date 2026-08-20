package com.trustmesh.app.core.identity

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

class LocalContactIdentityResolver(private val context: Context) : CallerIdentityResolver {

    internal var hasContactsPermissionForTesting: Boolean? = null

    override suspend fun resolve(phoneNumber: String): ResolvedCaller {
        var displayName: String? = null
        var isKnown = false
        
        val hasContactsPermission = hasContactsPermissionForTesting ?: try {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        
        Log.d("TrustMeshIdentity", "contactsPermission=${if (hasContactsPermission) "GRANTED" else "DENIED"}")
        Log.d("TrustMeshIdentity", "lookupStarted=true")
        
        if (hasContactsPermission) {
            try {
                val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
                
                // 1. Try raw query
                val rawUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
                context.contentResolver.query(rawUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            displayName = cursor.getString(nameIndex)
                            isKnown = true
                            Log.d("TrustMeshIdentity", "rawLookupResult=FOUND name=$displayName")
                        }
                    }
                }
                if (!isKnown) {
                    Log.d("TrustMeshIdentity", "rawLookupResult=NOT_FOUND")
                }
                
                // 2. Try normalized query if raw failed
                if (!isKnown) {
                    val normalizedNumber = PhoneNumberNormalizer.normalize(phoneNumber)
                    if (normalizedNumber != phoneNumber) {
                        val normalizedUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalizedNumber))
                        context.contentResolver.query(normalizedUri, projection, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                                if (nameIndex >= 0) {
                                    displayName = cursor.getString(nameIndex)
                                    isKnown = true
                                    Log.d("TrustMeshIdentity", "normalizedLookupResult=FOUND name=$displayName")
                                }
                            }
                        }
                        if (!isKnown) {
                            Log.d("TrustMeshIdentity", "normalizedLookupResult=NOT_FOUND")
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e("TrustMeshIdentity", "SecurityException during contacts lookup", e)
            } catch (e: Exception) {
                Log.e("TrustMeshIdentity", "Error looking up contact", e)
            }
        }
        
        if (isKnown) {
            Log.d("TrustMeshIdentity", "lookupResult=FOUND")
            Log.d("TrustMeshIdentity", "contactName=$displayName")
            Log.d("TrustMeshIdentity", "identitySource=LOCAL_CONTACT")
            
            return ResolvedCaller(
                identity = CallerIdentity(
                    phoneNumber = phoneNumber,
                    displayName = displayName,
                    identityType = IdentityType.PERSON,
                    confidence = Confidence.HIGH,
                    source = IdentitySource.LOCAL_CONTACT,
                    isKnown = true
                )
            )
        }
        
        Log.d("TrustMeshIdentity", "lookupResult=NOT_FOUND")
        return ResolvedCaller(
            identity = CallerIdentity(
                phoneNumber = phoneNumber,
                displayName = null,
                identityType = IdentityType.UNKNOWN,
                confidence = Confidence.NONE,
                source = IdentitySource.UNKNOWN,
                isKnown = false
            )
        )
    }
}
