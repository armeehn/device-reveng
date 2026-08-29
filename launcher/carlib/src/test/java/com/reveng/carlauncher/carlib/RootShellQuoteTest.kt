package com.reveng.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [RootShell.quote] is the only thing between a vendor string and a root shell.
 *
 * Every backend hands the command to exactly one shell, and [SysVar.putString] interpolates a
 * keyname and a value straight into a `content update` line. Those values come from the vendor
 * settings table and from text fields in the Advanced browser — so an unquoted `;`, `$(…)` or
 * backtick does not corrupt a setting, it runs as root on the head unit.
 *
 * The assertions below are split in two. The literal ones pin the encoding; the executed ones hand
 * the quoted string to a real POSIX shell and check that exactly the original bytes come back,
 * which is the property that actually matters and the only spec `sh` recognises.
 */
class RootShellQuoteTest {

    /** Strings the launcher can realistically interpolate, plus the ones an attacker would pick. */
    private val hostile = listOf(
        "",
        "plain",
        "Sys_CarType",
        "with a space",
        "it's",
        "'",
        "''",
        "'; id; '",
        "\$(id)",
        "`id`",
        "\${HOME}",
        "a && rm -rf /",
        "a | tee /dev/null",
        "a; b",
        "a\nb",
        "tab\there",
        "back\\slash",
        "*",
        "~",
        "#comment",
        "-n",
        "quote\"inside",
    )

    /** The one escape a single-quoted POSIX word admits: `'\''` — close, escaped quote, reopen. */
    private val escapedQuote = """'\''"""

    @Test
    fun aPlainValueBecomesOneSingleQuotedWord() {
        assertEquals("'plain'", RootShell.quote("plain"))
    }

    @Test
    fun anEmptyValueStaysAnEmptyArgument() {
        // Not the empty string: `cmd ` would drop the argument entirely and shift the ones after it.
        assertEquals("''", RootShell.quote(""))
    }

    @Test
    fun anEmbeddedQuoteClosesAndReopens() {
        assertEquals("'it" + escapedQuote + "s'", RootShell.quote("it's"))
    }

    @Test
    fun everyResultIsSingleQuotedEndToEnd() {
        hostile.forEach { raw ->
            val quoted = RootShell.quote(raw)

            assertTrue("'$raw' -> $quoted", quoted.startsWith("'") && quoted.endsWith("'"))
            assertTrue("'$raw' -> $quoted", quoted.length >= 2)
        }
    }

    @Test
    fun noBareQuoteSurvivesInside() {
        // A single quote left unescaped in the middle would end the word early and expose whatever
        // follows to the shell. Strip the legal escape sequence; nothing quote-shaped may remain.
        hostile.forEach { raw ->
            val inner = RootShell.quote(raw).drop(1).dropLast(1)

            assertFalse(
                "bare quote inside the result for '$raw'",
                inner.replace(escapedQuote, "").contains('\''),
            )
        }
    }

    @Test
    fun aRealShellReadsBackExactlyWhatWentIn() {
        // The encoding is only correct if `sh` agrees. printf %s writes the argument with no
        // interpretation, so any difference is the quoting failing.
        assumeTrue("no POSIX shell on this host", File("/bin/sh").canExecute())

        hostile.forEach { raw ->
            assertEquals(raw, runInShell("printf %s ${RootShell.quote(raw)}"))
        }
    }

    @Test
    fun aRealShellDoesNotExecuteAnInjectedCommand() {
        assumeTrue("no POSIX shell on this host", File("/bin/sh").canExecute())

        // If quoting leaked, the marker would be replaced by the output of `id` or by nothing.
        val payloads = listOf("'; echo LEAKED; '", "\$(echo LEAKED)", "`echo LEAKED`", "x && echo LEAKED")

        payloads.forEach { payload ->
            assertEquals(payload, runInShell("printf %s ${RootShell.quote(payload)}"))
        }
    }

    @Test
    fun quotingAnAlreadyQuotedValueYieldsALiteral() {
        // Quoting an already-quoted value must produce a *literal* containing the quotes, so a
        // double-quote bug shows up as a wrong value rather than as an unquoted one.
        assumeTrue("no POSIX shell on this host", File("/bin/sh").canExecute())

        val once = RootShell.quote("value")

        assertEquals(once, runInShell("printf %s ${RootShell.quote(once)}"))
    }

    @Test
    fun resultOkTracksTheExitCodeOnly() {
        // RootShell.Result.ok gates every write path in SysVar; stderr output on a zero exit is
        // normal for `content` and must not read as failure.
        assertTrue(RootShell.Result(0, listOf("out"), listOf("a warning")).ok)
        assertFalse(RootShell.Result(1, emptyList(), emptyList()).ok)
        assertFalse(RootShell.Result(-1, emptyList(), emptyList()).ok)
    }

    @Test
    fun stdoutRejoinsTheLinesItWasSplitInto() {
        assertEquals("a\nb", RootShell.Result(0, listOf("a", "b"), emptyList()).stdout)
        assertEquals("", RootShell.Result(0, emptyList(), emptyList()).stdout)
        // isRootAvailable() looks for "uid=0" in here, so a single line must not gain a separator.
        assertEquals("uid=0(root)", RootShell.Result(0, listOf("uid=0(root)"), emptyList()).stdout)
    }

    private fun runInShell(command: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(false).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return out
    }
}
