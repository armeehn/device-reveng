package com.ripostelabs.car

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** CarService's live subset, handed to [EventCompatService] in the same process. */
object EventHub {
    @Volatile
    var calls: EventCalls? = null
}

/**
 * The vendor eventcenter binder for stock-built apps (os/CARHAL.md "IEventService
 * compatibility"). No bind permission: reads answer anyone, changes check CONTROL per call.
 * It rides CarService's owner, so it starts CarService; calls get zero replies until it is up.
 */
class EventCompatService : Service() {

    override fun onCreate() {
        super.onCreate()
        startService(Intent(this, CarService::class.java))
    }

    override fun onBind(intent: Intent?): IBinder = EventBinder { EventHub.calls }
}
