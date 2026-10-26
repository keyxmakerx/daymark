package com.daymark.app.ui.components

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writing is never lost on the way out of an editor, and the bin always asks (#486). Read as text,
 * because the Back gesture cannot be driven on a plain JVM. Every check is first shown a planted
 * example (CLAUDE.md §5). Runs through `tools/jvm-source-tests.sh`.
 */
class EditorGuardsSourceTest {

    private companion object {
        const val UI = "app/src/main/java/com/daymark/app/ui/"
        val EDITORS = listOf(
            UI + "entry/EntryEditorScreen.kt",
            UI + "journal/JournalEditorScreen.kt",
            UI + "cbt/ThoughtRecordEditorScreen.kt",
            UI + "goals/GoalEditorScreen.kt",
        )
        /** The two that used to leave with a bare onDone: they must ask instead. */
        val ASKING = listOf(UI + "entry/EntryEditorScreen.kt", UI + "goals/GoalEditorScreen.kt")

        val BARE_DELETE = Regex("""IconButton\(onClick = viewModel::delete\)""")
        val BARE_LEAVE = Regex("""IconButton\(onClick = onDone\)""")
    }

    private fun code(rel: String) = codeOnly(repoFile(rel).readText())

    @Test
    fun every_editor_handles_system_back() {
        for (f in EDITORS) assertTrue("$f lets system Back past", code(f).contains("BackHandler"))
        assertFalse(code(EDITORS[0]).replace("BackHandler", "Unit").contains("BackHandler"))
    }

    @Test
    fun the_bin_asks_first() {
        val hits = EDITORS.filter { BARE_DELETE.containsMatchIn(code(it)) }
        assertEquals(emptyList<String>(), hits)
        val planted = code(EDITORS[1]).replace("IconButton(onClick = { confirmingDelete = true })", "IconButton(onClick = viewModel::delete)")
        assertTrue(BARE_DELETE.containsMatchIn(planted))
        for (f in EDITORS.drop(1)) assertTrue("$f has no delete dialog", code(f).contains("ConfirmDeleteDialog("))
    }

    @Test
    fun the_back_arrow_asks_where_changes_would_be_lost() {
        for (f in ASKING) {
            val c = code(f)
            assertFalse("$f leaves with a bare onDone", BARE_LEAVE.containsMatchIn(c))
            assertTrue(c.contains("UnsavedChangesDialog("))
            assertTrue(c.contains("viewModel.hasUnsavedChanges()"))
        }
        assertTrue(BARE_LEAVE.containsMatchIn(code(ASKING[0]).replace("IconButton(onClick = { leave() })", "IconButton(onClick = onDone)")))
    }

    /** The lock survives a rotation but never the process: a ViewModel, not remember or rememberSaveable. */
    @Test
    fun the_lock_survives_rotation_and_not_process_death() {
        val c = code("app/src/main/java/com/daymark/app/MainActivity.kt")
        val forgetsOrPersists = Regex("""var unlocked by (?:remember|rememberSaveable)\b""")
        assertFalse(forgetsOrPersists.containsMatchIn(c))
        assertTrue(forgetsOrPersists.containsMatchIn("var unlocked by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(c.contains("class UnlockState : androidx.lifecycle.ViewModel()"))
    }
}
