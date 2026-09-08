package com.ripostelabs.carlauncher.service

import android.app.Activity
import android.os.Bundle

/**
 * Starts the capture the moment the CANable is plugged in, then gets out of the way.
 *
 * USB_DEVICE_ATTACHED has to be declared on an activity for Android to grant device permission
 * without prompting, but the work belongs in a service. So this shows nothing, starts the
 * service and finishes immediately.
 *
 * Deliberately separate from the launcher's HOME activity: tying the launcher's lifecycle to an
 * adapter being plugged in would be a real regression for a car that boots without one.
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CanCaptureService.start(this)
        finish()
    }
}
