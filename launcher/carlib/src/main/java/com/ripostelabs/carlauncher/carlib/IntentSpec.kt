package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.content.Intent

/**
 * An outbound broadcast or activity launch described WITHOUT `android.content.Intent`, so a
 * plain JVM test can check exactly what a vendor command will carry. [toIntent] is the only
 * framework touch, done at send time.
 */
data class IntentSpec(
    val action: String,
    /** Explicit target package, or null for an implicit intent. */
    val packageName: String? = null,
    val ints: Map<String, Int> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    /** Explicit target class inside [packageName] (activity launches); ignored without one. */
    val className: String? = null,
) {

    fun toIntent(): Intent {
        val intent = Intent(action)
        if (packageName != null && className != null) {
            intent.setClassName(packageName, className)
        } else if (packageName != null) {
            intent.setPackage(packageName)
        }
        ints.forEach { (key, value) -> intent.putExtra(key, value) }
        strings.forEach { (key, value) -> intent.putExtra(key, value) }
        return intent
    }

    /** Fire as a broadcast. Failures are the receiver's business; nothing is thrown. */
    fun broadcast(context: Context) {
        runCatching { context.sendBroadcast(toIntent()) }
    }

    /** Start as an activity from a non-Activity context. @return false when nothing resolved. */
    fun start(context: Context): Boolean = runCatching {
        context.startActivity(toIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
