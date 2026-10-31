package com.daymark.app.ui.settings

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.replaceOnce
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Check a backup opens" (#524) reads a file and changes nothing on the phone, and a file that
 * does not open is named by its consequence. Read as text, because the check needs a content
 * resolver and a database. Every check is first shown a planted breach (CLAUDE.md §5). Runs
 * through `tools/jvm-source-tests.sh`.
 */
class BackupCheckSourceTest {

    private companion object {
        const val VM = "app/src/main/java/com/daymark/app/ui/settings/SettingsViewModel.kt"
        const val SCREEN = "app/src/main/java/com/daymark/app/ui/settings/SettingsScreen.kt"
        const val MANAGER = "app/src/main/java/com/daymark/app/backup/BackupManager.kt"

        /** Anything that would change the phone: a restore, a write, a DAO, a preference. */
        val CHANGES = Regex("""importFromJson|openOutputStream|Dao\b|Dao\.|\.edit\(\)|crisisStore|settings\.\w+\s*=""")
    }

    private fun checkBody(vm: String) = vm.substringAfter("fun checkBackup(").substringBefore("fun dismissBackupCheck(")

    @Test
    fun the_check_only_reads() {
        val body = checkBody(codeOnly(repoFile(VM).readText()))
        assertTrue(body.contains("backupManager.read(it)"))
        assertTrue(body.contains("openInputStream"))
        assertFalse(CHANGES.containsMatchIn(body))
        assertTrue(CHANGES.containsMatchIn(replaceOnce(body, "backupManager.read(it)", "backupManager.importFromJson(it)")))

        val manager = codeOnly(repoFile(MANAGER).readText())
        val read = manager.substringAfter("fun read(jsonText: String): Reading {").substringBefore("\n    }")
        assertTrue(read.contains("decodeFromString<BackupData>"))
        assertFalse(CHANGES.containsMatchIn(read))
    }

    /** The refusals say what the person can and cannot do, never why the file is broken. */
    @Test
    fun a_file_that_does_not_open_is_named_by_its_consequence() {
        val screen = repoFile(SCREEN).readText()
        val dialog = screen.substringAfter("private fun BackupCheckDialog(").substringBefore("private fun countOf(")
        assertTrue(dialog.contains("\"This file can't be restored\""))
        assertTrue(dialog.contains("Nothing on this phone was changed."))
        val guesses = Regex("""(?i)corrupt|damaged|truncated|broken|wrong format|invalid""")
        assertFalse(guesses.containsMatchIn(dialog))
        assertTrue(guesses.containsMatchIn(dialog.replace("could not read it as a backup", "found the file corrupt")))
        assertTrue(screen.contains("checkLauncher.launch("))
    }
}
