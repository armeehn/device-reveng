package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v0.4.3.7 — the pure half of [CrashLog]: record formatting, the bounded ring, and reading a stored
 * log back. The corrupt-log cases matter most: this file is written by a process that is already
 * dying, so half-written records are the normal failure mode, not an exotic one.
 */
class CrashLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val version = "0.4.3.7 (67)"

    /** Stands in for the platform's own default handler, which is what kills the process. */
    private class Delegate : Thread.UncaughtExceptionHandler {
        var seen: Throwable? = null
        override fun uncaughtException(thread: Thread, error: Throwable) {
            seen = error
        }
    }

    private fun record(at: Long, message: String) =
        CrashLog.format(at, "main", version, RuntimeException(message))

    @Test
    fun formatCarriesTimeThreadVersionAndTrace() {
        val parsed = CrashLog.parse(CrashLog.join(listOf(record(1_700_000_000_000L, "boom"))))

        assertEquals(1, parsed.size)
        assertEquals(1_700_000_000_000L, parsed[0].timeMillis)
        assertEquals("main", parsed[0].thread)
        assertEquals(version, parsed[0].version)
        assertTrue(parsed[0].trace.startsWith("java.lang.RuntimeException: boom"))
        assertTrue(parsed[0].trace.contains("\tat "))
        assertEquals("java.lang.RuntimeException: boom", parsed[0].summary)
    }

    @Test
    fun splitAndJoinRoundTripWithoutGrowing() {
        val records = listOf(record(1L, "a"), record(2L, "b"))
        val once = CrashLog.join(records)

        assertEquals(records, CrashLog.split(once))
        assertEquals(once, CrashLog.join(CrashLog.split(once)))
    }

    @Test
    fun evictByCountKeepsNewest() {
        val records = (1L..5L).map { record(it, "boom $it") }

        val kept = CrashLog.evict(records, maxRecords = 3, maxBytes = Int.MAX_VALUE)

        assertEquals(records.takeLast(3), kept)
    }

    @Test
    fun evictByBytesKeepsNewest() {
        val records = (1L..5L).map { record(it, "boom $it") }
        val budget = CrashLog.join(records.takeLast(2)).toByteArray().size

        val kept = CrashLog.evict(records, maxRecords = 100, maxBytes = budget)

        assertEquals(records.takeLast(2), kept)
    }

    @Test
    fun evictKeepsALoneOversizedRecord() {
        val only = listOf(record(1L, "boom"))

        assertEquals(only, CrashLog.evict(only, maxRecords = 10, maxBytes = 1))
    }

    @Test
    fun formatBoundsARunawayTrace() {
        val huge = RuntimeException("x".repeat(CrashLog.MAX_TRACE_CHARS * 2))

        val trace = CrashLog.parse(CrashLog.join(listOf(CrashLog.format(1L, "main", version, huge)))).single().trace

        assertTrue(trace.length < CrashLog.MAX_TRACE_CHARS + 100)
        assertTrue(trace.endsWith("truncated"))
    }

    @Test
    fun parseReadsWhatItCanFromATruncatedLog() {
        // The tail record is what a power cut mid-write leaves behind: a header, part of a body.
        val log = CrashLog.join(listOf(record(1_000L, "first"))) + "----- crash 2000 -----\nthread: fin"

        val parsed = CrashLog.parse(log)

        assertEquals(2, parsed.size)
        assertEquals("java.lang.RuntimeException: first", parsed[0].summary)
        assertEquals(2_000L, parsed[1].timeMillis)
        assertEquals("fin", parsed[1].thread)
        assertEquals("", parsed[1].version)
        assertEquals("", parsed[1].trace)
        assertEquals("", parsed[1].summary)
    }

    @Test
    fun parseDropsHeaderlessLeadingGarbage() {
        val log = "\tat com.example.Foo.bar(Foo.kt:1)\nnot a record at all\n" +
            CrashLog.join(listOf(record(1_000L, "first")))

        val parsed = CrashLog.parse(log)

        assertEquals(1, parsed.size)
        assertEquals(1_000L, parsed[0].timeMillis)
    }

    @Test
    fun parseSurvivesAnUnreadableTimestamp() {
        val log = "----- crash notanumber -----\nthread: main\nversion: $version\n\nboom\n"

        val parsed = CrashLog.parse(log)

        assertEquals(1, parsed.size)
        assertEquals(0L, parsed[0].timeMillis)
        assertEquals("boom", parsed[0].trace)
    }

    @Test
    fun parseOfEmptyOrBlankLogIsEmpty() {
        assertEquals(emptyList<CrashRecord>(), CrashLog.parse(""))
        assertEquals(emptyList<CrashRecord>(), CrashLog.parse("\n\n\n"))
    }

    @Test
    fun handlerRecordsThenDelegates() {
        val log = File(temp.root, "crashes.log")
        val delegate = Delegate()
        val boom = IllegalStateException("wheels came off")

        CrashLog.handler(log, version, delegate).uncaughtException(Thread.currentThread(), boom)

        val stored = CrashLog.parse(log.readText())
        assertEquals(1, stored.size)
        assertEquals(Thread.currentThread().name, stored[0].thread)
        assertEquals(version, stored[0].version)
        assertTrue(stored[0].trace.startsWith("java.lang.IllegalStateException: wheels came off"))
        assertSame(boom, delegate.seen)
    }

    @Test
    fun handlerDelegatesEvenWhenTheWriteFails() {
        // No such directory, so the FileOutputStream throws — the original crash must still be
        // handed on, or the handler would hide the very bug it exists to record.
        val log = File(File(temp.root, "missing"), "crashes.log")
        val delegate = Delegate()
        val boom = IllegalStateException("wheels came off")

        CrashLog.handler(log, version, delegate).uncaughtException(Thread.currentThread(), boom)

        assertSame(boom, delegate.seen)
    }

    @Test
    fun handlerWithNoPreviousHandlerStillReturns() {
        val log = File(temp.root, "crashes.log")

        CrashLog.handler(log, version, null)
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(1, CrashLog.parse(log.readText()).size)
    }

    @Test
    fun repeatedCrashesStayInsideTheRing() {
        val log = File(temp.root, "crashes.log")
        val handler = CrashLog.handler(log, version, Delegate())

        repeat(CrashLog.MAX_RECORDS + 5) {
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom $it"))
        }

        val stored = CrashLog.parse(log.readText())
        assertEquals(CrashLog.MAX_RECORDS, stored.size)
        assertTrue(log.length() <= CrashLog.MAX_BYTES.toLong())
        assertTrue(stored.last().summary.endsWith("boom " + (CrashLog.MAX_RECORDS + 4)))
    }
}
