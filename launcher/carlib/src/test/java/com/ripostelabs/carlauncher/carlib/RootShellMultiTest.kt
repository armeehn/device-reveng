package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vararg [RootShell.exec] used to join its commands with `&&`, which made every command
 * conditional on the one before it: "repair all" stopped at the first non-zero exit and said
 * nothing. These tests pin both halves of the fix — everything runs, and the caller can see which
 * command failed.
 *
 * They drive [RootShell.execEach] with a fake backend, so no `su` and no Android framework is
 * involved.
 */
class RootShellMultiTest {

    private fun ok(out: String = "") = RootShell.Result(0, listOf(out), emptyList())
    private fun fail(code: Int = 1) = RootShell.Result(code, emptyList(), listOf("boom"))

    @Test
    fun `every command runs even after one fails`() {
        val ran = mutableListOf<String>()
        val commands = listOf("pm grant A", "pm grant B", "cmd notification allow_listener C")

        RootShell.execEach(commands) { command ->
            ran += command
            if (command == "pm grant A") fail() else ok()
        }

        assertEquals(commands, ran)
    }

    @Test
    fun `failures name the commands that failed`() {
        val res = RootShell.execEach(listOf("first", "second", "third")) { command ->
            if (command == "second") fail(code = 7) else ok()
        }

        assertFalse(res.ok)
        assertEquals(listOf("second"), res.failures.map { it.command })
        assertEquals(7, res.failures.single().result.code)
    }

    @Test
    fun `ok only when every command exited zero`() {
        assertTrue(RootShell.execEach(listOf("a", "b")) { ok() }.ok)
        assertFalse(RootShell.execEach(listOf("a", "b")) { fail() }.ok)
    }

    @Test
    fun `outcomes keep order and pair each command with its own result`() {
        val res = RootShell.execEach(listOf("a", "b")) { command -> ok(out = "out-$command") }

        assertEquals(listOf("a", "b"), res.outcomes.map { it.command })
        assertEquals(listOf("out-a", "out-b"), res.outcomes.map { it.result.stdout })
        assertTrue(res.failures.isEmpty())
    }

    @Test
    fun `no commands is a success with nothing run`() {
        var calls = 0
        val res = RootShell.execEach(emptyList()) { calls++; ok() }

        assertEquals(0, calls)
        assertTrue(res.ok)
    }
}
