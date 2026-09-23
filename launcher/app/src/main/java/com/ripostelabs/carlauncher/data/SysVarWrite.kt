package com.ripostelabs.carlauncher.data

/**
 * Which path persisted a SysVar write.
 *
 * The vendor settings app never writes the provider directly while the gateway is bound: every
 * write goes through `IEventService.changeSetup(key, value)` so the gateway persists the row AND
 * reacts to it (MCU frames, nav-bar geometry, broadcasts). The provider is its fallback when
 * unbound (`SystemPropertiesHelps.java:54-62`). We mirror that order.
 *
 * On Riposte OS 0.2 neither exists: the launcher owns the MCU and keeps the rows itself, so the
 * local store goes first and is the truth there. It is absent (null) on the vendor slot.
 *
 *     setString ──▶ local store ──ok──▶ LOCAL
 *                        │ absent / refused
 *                        ▼
 *                   gateway.changeSetup ──ok──▶ GATEWAY
 *                          │ unbound / threw
 *                          ▼
 *                   provider (root shell) ──ok──▶ PROVIDER
 *                          │ failed
 *                          ▼
 *                        FAILED
 */
enum class WriteRoute { LOCAL, GATEWAY, PROVIDER, FAILED }

/** A write attempt: true when it stuck. */
typealias SysVarSink = (key: String, value: String) -> Boolean

/**
 * Persist [key]=[value] through [local] first, then [gateway], then [provider]. Pure: the sinks
 * do the I/O. [local] is null off the owner path; [gateway] may be null when the caller has no
 * service handle at all.
 */
fun persistSysVar(
    key: String,
    value: String,
    gateway: SysVarSink?,
    provider: SysVarSink,
    local: SysVarSink? = null,
): WriteRoute {
    if (local != null && local(key, value)) {
        return WriteRoute.LOCAL
    }

    if (gateway != null && gateway(key, value)) {
        return WriteRoute.GATEWAY
    }

    if (provider(key, value)) {
        return WriteRoute.PROVIDER
    }

    return WriteRoute.FAILED
}
