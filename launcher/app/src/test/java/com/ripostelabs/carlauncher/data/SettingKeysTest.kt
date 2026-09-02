package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [SettingKeys] is a hand transcription of vendor keynames out of a decompiled `SysProviderOpt`,
 * grown one settings screen at a time. Nothing in the type system stops two constants carrying the
 * same string, and the failure that causes is quiet and confusing: two unrelated controls write the
 * same vendor key, so moving one silently moves the other, and neither screen looks wrong.
 *
 * Read reflectively rather than listed by hand — a list would need editing every time a key is
 * added, which is exactly when the check stops being run.
 */
class SettingKeysTest {

    private val keys: Map<String, String> = SettingKeys::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .onEach { it.isAccessible = true }
        .associate { it.name to (it.get(null) as String) }

    @Test
    fun theTableIsNotEmpty() {
        // A reflection filter that matched nothing would make every other test here vacuous.
        assertTrue("no String constants found on SettingKeys", keys.size > 50)
    }

    @Test
    fun noTwoConstantsShareAKeyname() {
        val byValue = keys.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }

        assertEquals("duplicate vendor keynames: $byValue", emptyMap<String, List<String>>(), byValue)
    }

    @Test
    fun keynamesAreNotAccidentallyEqualIgnoringCase() {
        // The vendor's own casing is inconsistent (Sys_, sys_, SYS_) and the provider is
        // case-sensitive, so two constants differing only in case are far more likely to be a
        // transcription slip than two real keys.
        val byLowercase = keys.entries
            .groupBy({ it.value.lowercase() }, { it.key })
            .filterValues { it.size > 1 }

        assertEquals("keynames differing only in case: $byLowercase", emptyMap<String, List<String>>(), byLowercase)
    }

    @Test
    fun keynamesAreCleanTokens() {
        // These go straight into a `content update --where keyname='…'` line. Whitespace or a quote
        // would mean a transcription error survived all the way to a root shell.
        keys.forEach { (name, value) ->
            assertTrue("$name is blank", value.isNotBlank())
            assertEquals("$name has surrounding whitespace", value.trim(), value)
            assertTrue("$name contains whitespace", value.none { it.isWhitespace() })
            assertTrue("$name contains a quote", value.none { it == '\'' || it == '"' })
        }
    }
}
