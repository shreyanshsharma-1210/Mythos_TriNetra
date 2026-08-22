package com.trustmesh.app.vcd.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One entry from the phone's own address book. */
data class PhoneContact(
    val id: Long,
    val name: String,
    val number: String?,
)

/**
 * Reads the device address book, so the dialler shows the people the user actually knows rather
 * than a list of network addresses.
 *
 * Read-only and never written anywhere: the names are used to populate a list and are gone when the
 * screen closes. Nothing here is stored, uploaded, or matched against anything — the app already
 * holds no network capability for anything except the call itself.
 *
 * A contact is a person, not a device. Tapping one places a call to a TRINETRA device on the local
 * network, and the dialler says so plainly rather than implying the tap dials their phone number.
 */
object PhoneContacts {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun load(context: Context, limit: Int = 500): List<PhoneContact> =
        withContext(Dispatchers.IO) {
            if (!hasPermission(context)) return@withContext emptyList()

            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )

            val out = LinkedHashMap<Long, PhoneContact>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(projection[0])
                    val nameCol = cursor.getColumnIndexOrThrow(projection[1])
                    val numberCol = cursor.getColumnIndexOrThrow(projection[2])
                    while (cursor.moveToNext() && out.size < limit) {
                        val id = cursor.getLong(idCol)
                        // One row per phone number; a person with three numbers should still be
                        // one row in the list.
                        if (out.containsKey(id)) continue
                        val name = cursor.getString(nameCol)?.trim().orEmpty()
                        if (name.isEmpty()) continue
                        out[id] = PhoneContact(id, name, cursor.getString(numberCol)?.trim())
                    }
                }
            }
            out.values.toList()
        }
}
