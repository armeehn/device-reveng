package com.ripostelabs.car

import com.szchoiceway.eventcenter.IEventService

/** What a call writes after writeNoException; the zero of each is the safe default. */
enum class Reply { VOID, INT, LONG, FLOAT, STRING, ARRAY, BINDER }

/** The vendor call a transaction code names, and the shape of its reply. */
data class EventCall(val name: String, val reply: Reply)

/**
 * Every IEventService transaction, read off the stub generated from carlib's AIDL. An unserved
 * call still gets a reply of the shape its caller unmarshals: a short reply would throw in the
 * stock app, and a missing one would leave it waiting.
 */
object EventTable {

    private const val PREFIX = "TRANSACTION_"

    val calls: Map<Int, EventCall> by lazy { build() }

    // The stub's TRANSACTION_<name> fields carry the vendor ordinals; the interface method of
    // the same name carries the return type. AIDL forbids overloads, so names are unique.
    private fun build(): Map<Int, EventCall> {
        val methods = IEventService::class.java.methods.associateBy { it.name }
        return IEventService.Stub::class.java.declaredFields
            .filter { it.name.startsWith(PREFIX) }
            .associate { field ->
                field.isAccessible = true
                val name = field.name.removePrefix(PREFIX)
                field.getInt(null) to EventCall(name, reply(methods.getValue(name).returnType))
            }
    }

    private fun reply(type: Class<*>): Reply = when {
        type == Void.TYPE -> Reply.VOID
        type == java.lang.Long.TYPE -> Reply.LONG
        type == java.lang.Float.TYPE -> Reply.FLOAT
        // boolean, byte and int all go out as one int.
        type.isPrimitive -> Reply.INT
        type == String::class.java -> Reply.STRING
        type.isArray -> Reply.ARRAY
        // getCameraService, the one interface the vendor returns.
        else -> Reply.BINDER
    }
}
