package com.ripostelabs.car

internal const val READ_PERMISSION = "com.ripostelabs.car.permission.READ"
internal const val CONTROL_PERMISSION = "com.ripostelabs.car.permission.CONTROL"

/** What a binder call needs from its caller (os/CARHAL.md "Permissions"). */
enum class Access { READ, CONTROL }

/**
 * Checks the calling app before a call runs. [held] answers "does the caller hold this
 * permission?"; on the unit it is Context.checkCallingOrSelfPermission, in tests a set.
 * CONTROL implies READ, so the launcher needs one grant, not two.
 */
class Gate(private val held: (String) -> Boolean) {

    fun enforce(access: Access) {
        if (held(CONTROL_PERMISSION)) {
            return
        }
        if (access == Access.READ && held(READ_PERMISSION)) {
            return
        }
        val needed = if (access == Access.READ) READ_PERMISSION else CONTROL_PERMISSION
        throw SecurityException("caller lacks $needed")
    }
}
