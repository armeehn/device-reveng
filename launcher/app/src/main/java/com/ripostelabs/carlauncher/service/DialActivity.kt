package com.ripostelabs.carlauncher.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ripostelabs.carlauncher.MainActivity
import com.ripostelabs.carlauncher.ui.PhoneLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * RAV4-162 — "call from contact": the suite Contacts app's Call button lands here.
 *
 * ```
 *  Contacts ──ACTION_CALL tel:…──▶ DialActivity ──DialRequests──▶ MainActivity ──▶ BtCarKit.dial
 *            (sender holds CALL_PHONE)  (no UI)     (same process)   (Phone screen)   / VendorBt.dial
 * ```
 *
 * A no-UI trampoline, like [UsbAttachActivity]: the manifest guards it with CALL_PHONE, so only
 * an app the driver (or the image) allowed to place calls can make the car dial. The number
 * crosses to [MainActivity] through [DialRequests], never an intent extra, since the HOME
 * activity is exported and anything could put an extra on it.
 */
class DialActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val number = PhoneLogic.telNumber(intent?.data?.schemeSpecificPart)
        if (number != null) {
            DialRequests.post(number)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}

/** One pending number from [DialActivity]; [MainActivity] takes it once it is up. */
object DialRequests {

    private val pending = MutableStateFlow<String?>(null)

    val numbers: StateFlow<String?> get() = pending

    fun post(number: String) {
        pending.value = number
    }

    /** Take the pending number, so a later collect does not dial it twice. */
    fun take(): String? = pending.getAndUpdate { null }
}
