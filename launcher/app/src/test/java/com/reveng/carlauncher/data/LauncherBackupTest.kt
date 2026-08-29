package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The file half of restore ([LauncherBackup.restoreInto]). The property that matters: the live
 * datastore directory is either untouched or fully swapped — the old one-pass copy could fail
 * mid-way and leave a silent mix of two snapshots behind a "restore failed" answer.
 */
class LauncherBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dirWithPrefs(name: String, files: Map<String, String>): File {
        val dir = temp.newFolder(name)
        files.forEach { (n, content) -> File(dir, n).writeText(content) }
        return dir
    }

    @Test
    fun restoreSwapsEveryFileAndLeavesNoStages() {
        val live = dirWithPrefs("datastore", mapOf("a.preferences_pb" to "live-a"))
        val backup = dirWithPrefs(
            "backup",
            mapOf("a.preferences_pb" to "backup-a", "b.preferences_pb" to "backup-b"),
        )

        assertTrue(LauncherBackup.restoreInto(live, backup, log = {}))

        assertEquals("backup-a", File(live, "a.preferences_pb").readText())
        assertEquals("backup-b", File(live, "b.preferences_pb").readText())
        assertTrue(live.listFiles().orEmpty().none { it.name.endsWith(".restore-tmp") })
    }

    @Test
    fun failedStagingLeavesLiveFilesUntouched() {
        val live = dirWithPrefs("datastore", mapOf("a.preferences_pb" to "live-a"))
        val backup = dirWithPrefs(
            "backup",
            mapOf("a.preferences_pb" to "backup-a", "z.preferences_pb" to "unreadable"),
        )
        val poison = File(backup, "z.preferences_pb")
        // An unreadable source makes its staging copy throw. Meaningless when running as
        // root (root reads anything), so the case is skipped there rather than passing vacuously.
        assumeFalse("running as root", System.getProperty("user.name") == "root")
        assumeTrueSetUnreadable(poison)

        assertFalse(LauncherBackup.restoreInto(live, backup, log = {}))

        assertEquals("live-a", File(live, "a.preferences_pb").readText())
        assertFalse(File(live, "z.preferences_pb").exists())
        assertTrue(live.listFiles().orEmpty().none { it.name.endsWith(".restore-tmp") })
    }

    @Test
    fun emptyBackupRestoresNothing() {
        val live = dirWithPrefs("datastore2", mapOf("a.preferences_pb" to "live-a"))
        val backup = temp.newFolder("empty-backup")

        assertFalse(LauncherBackup.restoreInto(live, backup, log = {}))
        assertEquals("live-a", File(live, "a.preferences_pb").readText())
    }

    private fun assumeTrueSetUnreadable(file: File) {
        org.junit.Assume.assumeTrue(
            "filesystem ignores setReadable(false)",
            file.setReadable(false, false) && !file.canRead(),
        )
    }
}
