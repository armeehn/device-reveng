package com.ripostelabs.carlauncher.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.util.Log

/**
 * RAV4-152 — the phonebook's name for a caller's number, on Riposte OS 0.2.
 *
 * The stack's PBAP client syncs the phone's contacts into the contacts provider when the phone
 * connects (`PbapClientService`, account `com.android.bluetooth.pbapsink`), so the ordinary
 * [PhoneLookup] finds them. Stock btsuite read the same names off its module (`EVT_CONTACT_NAME`).
 * One number is looked up once: the car-kit refreshes many times during one ring.
 * Needs READ_CONTACTS (granted with `pm grant` like BLUETOOTH_CONNECT); without it, no name.
 */
class CallerNames(private val context: Context) {

    private var lastNumber: String? = null
    private var lastName: String? = null

    /** Blocking provider read; call off the main thread. Two collectors share the cache. */
    @Synchronized
    fun lookup(number: String?): String? {
        if (number.isNullOrBlank()) {
            return null
        }
        if (number == lastNumber) {
            return lastName
        }

        val name = query(number)
        lastNumber = number
        lastName = name
        return name
    }

    private fun query(number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.onFailure { Log.w(TAG, "caller lookup failed: ${it.message}") }.getOrNull()

    private companion object {
        const val TAG = "CallerNames"
    }
}
