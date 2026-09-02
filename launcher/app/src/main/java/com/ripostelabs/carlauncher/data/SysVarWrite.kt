package com.ripostelabs.carlauncher.data

/**
 * Which path persisted a SysVar write.
 *
 * The vendor settings app never writes the provider directly while the gateway is bound: every
 * write goes through `IEventService.changeSetup(key, value)` so the gateway persists the row AND
 * reacts to it (MCU frames, nav-bar geometry, broadcasts). The provider is its fallback when
 * unbound (`SystemPropertiesHelps.java:54-62`). We mirror that order.
 *
 *     setString ──▶ gateway.changeSetup ──ok──▶ GATEWAY
 *                          │ unbound / threw
 *                          ▼
 *                   provider (root shell) ──ok──▶ PROVIDER
 *                          │ failed
 *                          ▼
 *                        FAILED
 */
enum class WriteRoute { GATEWAY, PROVIDER, FAILED }

/** A write attempt: true when it landed. */
typealias SysVarSink = (key: String, value: String) -> Boolean

/**
 * Persist [key]=[value] through [gateway] first, then [provider]. Pure: the sinks do the I/O.
 * [gateway] may be null when the caller has no service handle at all.
 */
fun persistSysVar(
    key: String,
    value: String,
    gateway: SysVarSink?,
    provider: SysVarSink,
): WriteRoute {
    if (gateway != null && gateway(key, value)) {
        return WriteRoute.GATEWAY
    }

    if (provider(key, value)) {
        return WriteRoute.PROVIDER
    }

    return WriteRoute.FAILED
}
