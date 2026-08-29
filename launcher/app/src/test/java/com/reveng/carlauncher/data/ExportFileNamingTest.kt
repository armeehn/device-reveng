package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * [SysVarExport] and [LauncherBackup] both encode a timestamp into a name and parse it back out to
 * sort "newest first". The directories they read are on external storage, reachable over adb, so
 * they will contain files nobody in this codebase wrote: half-pulled downloads, an editor's `.swp`,
 * a folder a driver renamed. Parsing must answer null for those rather than throw, because both
 * `list()` calls run while a settings screen is composing.
 *
 * Only the pure name parsing is covered — `export`, `create`, `list` and `restore` all need an
 * Android `Context` for the external files directory. See the PR body.
 */
class ExportFileNamingTest {

    private val stamp = 1_700_000_000_000L

    @Test
    fun sysVarDumpNameRoundTrips() {
        assertEquals(stamp, SysVarExport.timestampOf(File("/tmp/sysvar-$stamp.json")))
    }

    @Test
    fun backupFolderNameRoundTrips() {
        assertEquals(stamp, LauncherBackup.timestampOf(File("/tmp/backup-$stamp")))
    }

    @Test
    fun onlyTheNameIsRead() {
        // A path with digits in a parent directory must not be mistaken for the timestamp.
        assertEquals(stamp, SysVarExport.timestampOf(File("/storage/0/1234/sysvar-$stamp.json")))
        assertEquals(stamp, LauncherBackup.timestampOf(File("/storage/0/1234/backup-$stamp")))
    }

    @Test
    fun foreignNamesParseAsNull() {
        val strangers = listOf(
            "readme.txt",
            "sysvar.json",            // right family, no stamp
            "sysvar-.json",           // an empty stamp
            "sysvar-latest.json",     // someone renamed it
            "sysvar-$stamp.json.swp", // an editor's leftovers
            ".sysvar-$stamp.json",    // a hidden partial download
        )

        strangers.forEach { name ->
            assertNull("SysVarExport parsed a stamp out of '$name'", SysVarExport.timestampOf(File(name)))
        }
    }

    @Test
    fun foreignFolderNamesParseAsNull() {
        listOf("backup", "backup-", "backup-old", "backups", "Backup-$stamp").forEach { name ->
            assertNull("LauncherBackup parsed a stamp out of '$name'", LauncherBackup.timestampOf(File(name)))
        }
    }

    @Test
    fun theTwoNamespacesDoNotOverlap() {
        // Both directories sit under the same external files dir. A dump must never parse as a
        // backup folder, or `restore` would be offered a JSON file to copy over the live stores.
        assertNull(LauncherBackup.timestampOf(File("sysvar-$stamp.json")))
        assertNull(SysVarExport.timestampOf(File("backup-$stamp")))
    }

    @Test
    fun newestFirstOrderingHoldsWithUnparseableNeighbours() {
        // This mirrors what list() does: sortedByDescending { timestampOf(it) ?: 0L }. The point of
        // the fallback is that a stray file sinks to the bottom instead of taking the sort down.
        val files = listOf(
            File("sysvar-1000.json"),
            File("junk.json"),
            File("sysvar-3000.json"),
            File("sysvar-2000.json"),
        )

        val sorted = files.sortedByDescending { SysVarExport.timestampOf(it) ?: 0L }.map { it.name }

        assertEquals(
            listOf("sysvar-3000.json", "sysvar-2000.json", "sysvar-1000.json", "junk.json"),
            sorted,
        )
    }
}
