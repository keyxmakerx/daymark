package com.daymark.app.data

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every table and index a migration creates is created in exactly the text Room exported for the
 * version it arrives at, and each hop creates exactly the tables and indices that version adds.
 *
 * ## Why this compares against Room's export
 *
 * Room checks a migrated database against the schema its annotations describe the first time the
 * app opens it, and throws if they differ — on somebody's phone. The instrumented `MigrationTest` is
 * what would catch that first, and nothing in CI runs it. What CI does produce is Room's own export
 * of each version under `app/schemas`, and its `createSql` is the text Room writes for every table
 * and index. A migration that says the same thing in the same words cannot disagree with it. The
 * schema tests beside this one ([PeopleSchemaTest], [CompanionSchemaTest]) compare a migration with
 * an account of Room written by hand; this compares it with Room.
 *
 * ## The newest version
 *
 * A new version's schema is generated in CI and committed from its output, never written by hand
 * (`.github/workflows/build.yml`, the step that fails when the exported Room schemas drift). Until
 * it is committed there is nothing to compare the newest migration with. That is not treated as a
 * pass: [onlyTheNewestSchemaMayBeUncommitted] asserts that the newest is the only migration without
 * one, CI's schema step fails for exactly that state, and the comparisons below cover the newest
 * the moment its schema lands.
 */
class MigrationSchemaExportTest {

    private companion object {
        const val DATABASE = "app/src/main/java/com/daymark/app/data/AppDatabase.kt"

        /** The oldest exported schema. `1.json` and `2.json` predate `exportSchema`. */
        const val OLDEST_SCHEMA = "app/schemas/com.daymark.app.data.AppDatabase/3.json"
    }

    private val database = strip(repoFile(DATABASE).readText())
    private val schemaDir: File = repoFile(OLDEST_SCHEMA).parentFile

    /** One migration: where it starts and ends, and the SQL it runs, statement by statement. */
    private data class Hop(val from: Int, val to: Int, val statements: List<String>)

    /** What one exported schema says: each table's and index's `createSql`, and which are which. */
    private data class Schema(val sql: Map<String, String>, val tables: Set<String>, val indices: Set<String>)

    private val hops: List<Hop> by lazy {
        val pattern = Regex("""val MIGRATION_(\d+)_(\d+) = object : Migration\((\d+), (\d+)\)""")
        pattern.findAll(database).map { m ->
            val body = database.substring(m.range.last).let { after ->
                val ends = listOf("val MIGRATION_", "val DEFAULT_ACTIVITIES").map { after.indexOf(it) }.filter { it >= 0 }
                if (ends.isEmpty()) after else after.substring(0, ends.min())
            }
            val statements = body.split("db.execSQL(").drop(1).map { chunk ->
                Regex("\"([^\"]*)\"").findAll(chunk).joinToString("") { it.groupValues[1] }
            }
            Hop(m.groupValues[3].toInt(), m.groupValues[4].toInt(), statements)
        }.sortedBy { it.from }.toList()
    }

    private val newest: Int by lazy { Regex("""version = (\d+),""").find(database)!!.groupValues[1].toInt() }

    private fun schema(version: Int): Schema? {
        val file = File(schemaDir, "$version.json").takeIf { it.isFile } ?: return null
        val db = (JsonReader(file.readText()).read() as Map<*, *>)["database"] as Map<*, *>
        val sql = LinkedHashMap<String, String>()
        val tables = LinkedHashSet<String>()
        val indices = LinkedHashSet<String>()
        for (entity in db["entities"] as List<*>) {
            entity as Map<*, *>
            val table = entity["tableName"] as String
            tables += table
            sql[table] = (entity["createSql"] as String).replace("\${TABLE_NAME}", table)
            for (index in (entity["indices"] as List<*>?).orEmpty()) {
                index as Map<*, *>
                val name = index["name"] as String
                indices += name
                sql[name] = (index["createSql"] as String).replace("\${TABLE_NAME}", table)
            }
        }
        return Schema(sql, tables, indices)
    }

    // ---------------------------------------------------------------------------------------------
    // Guards.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the migrations and the exported schemas were read`() {
        assertEquals("the chain does not run 1 → $newest", (1 until newest).map { it to it + 1 }, hops.map { it.from to it.to })
        assertTrue("implausibly few migrations: ${hops.size}", hops.size >= 18)
        val eighteen = schema(18)
        assertTrue("18.json did not read", eighteen != null && eighteen.tables.containsAll(listOf("people", "goal_steps")))
        assertEquals(
            "CREATE INDEX IF NOT EXISTS `index_person_notes_personId` ON `person_notes` (`personId`)",
            eighteen!!.sql["index_person_notes_personId"],
        )
        // The statements were sliced per migration: v18 runs nine, the four people tables with
        // their two indices and the three reception-ledger columns.
        assertEquals(9, hops.single { it.to == 18 }.statements.size)
    }

    // ---------------------------------------------------------------------------------------------
    // The comparisons.
    // ---------------------------------------------------------------------------------------------

    /**
     * Every `CREATE TABLE` and `CREATE INDEX` a migration runs is Room's `createSql` for that name in
     * the schema of the version the migration arrives at — for every version whose schema is
     * committed, the newest included once it is.
     */
    @Test
    fun `every CREATE a migration runs is Room's own text for the version it arrives at`() {
        var compared = 0
        for (hop in hops) {
            val target = schema(hop.to) ?: continue
            val wrong = mismatches(hop, target)
            assertEquals("MIGRATION_${hop.from}_${hop.to} is not in Room's words", emptyList<String>(), wrong)
            compared += creates(hop).size
        }
        // Non-vacuity: 3 → 18 alone create 27 tables and indices between them.
        assertTrue("only $compared statements were compared", compared >= 27)
    }

    /**
     * A hop creates exactly the tables and indices its version adds and removes none, for every hop
     * whose two schemas are both committed.
     *
     * A table new in the export and missing from the migration exists on a fresh install and not on
     * an upgraded phone; that is the case this is for.
     */
    @Test
    fun `each hop creates exactly what its version adds, and removes nothing`() {
        var checked = 0
        for (hop in hops) {
            val before = schema(hop.from) ?: continue
            val after = schema(hop.to) ?: continue
            assertEquals("MIGRATION_${hop.from}_${hop.to} creates the wrong set", added(before, after), created(hop))
            assertEquals("MIGRATION_${hop.from}_${hop.to} loses a table or an index", emptySet<String>(), removed(before, after))
            checked++
        }
        assertTrue("only $checked hops were checked", checked >= 15)
    }

    /**
     * The newest migration is the only one whose target schema may be uncommitted.
     *
     * An older one missing is a schema someone deleted, and the comparisons above would quietly skip
     * its migration; that fails here. The newest missing is the state between a version bump and the
     * commit of CI's export, and CI's schema step fails for it.
     */
    @Test
    fun onlyTheNewestSchemaMayBeUncommitted() {
        val missing = uncommittedTargets { schema(it) != null }
        assertTrue("schemas missing for older versions: $missing", missing.all { it == newest })
    }

    // ---------------------------------------------------------------------------------------------
    // The comparisons can fail.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the comparisons fail on a wrong word, a forgotten table and a deleted schema`() {
        val hop = hops.single { it.to == 18 }
        val target = schema(18)!!
        assertEquals(emptyList<String>(), mismatches(hop, target))

        // A wrong affinity in one column.
        val wrongWord = hop.copy(statements = hop.statements.map { it.replace("`name` TEXT NOT NULL", "`name` INTEGER NOT NULL") })
        assertNotEquals("mutation did not land", hop, wrongWord)
        assertEquals(listOf("people"), mismatches(wrongWord, target))

        // A table the version adds and the migration forgets.
        val forgot = hop.copy(statements = hop.statements.filterNot { it.startsWith("CREATE TABLE IF NOT EXISTS `people` (") })
        assertEquals(hop.statements.size - 1, forgot.statements.size)
        assertEquals(setOf("people"), added(schema(17)!!, target) - created(forgot))

        // An older schema deleted.
        assertEquals(listOf(12), uncommittedTargets { it != 12 && schema(it) != null }.filter { it != newest })
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    private fun creates(hop: Hop): List<String> = hop.statements.filter {
        it.startsWith("CREATE TABLE IF NOT EXISTS ") || it.startsWith("CREATE INDEX IF NOT EXISTS ") ||
            it.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS ")
    }

    private fun nameOf(statement: String): String = Regex("`([^`]+)`").find(statement)!!.groupValues[1]

    /** The names whose statement in [hop] is not Room's text for them in [target]. */
    private fun mismatches(hop: Hop, target: Schema): List<String> =
        creates(hop).filter { target.sql[nameOf(it)] != it }.map { nameOf(it) }

    private fun created(hop: Hop): Set<String> = creates(hop).map { nameOf(it) }.toSet()

    private fun added(before: Schema, after: Schema): Set<String> =
        (after.tables - before.tables) + (after.indices - before.indices)

    private fun removed(before: Schema, after: Schema): Set<String> =
        (before.tables - after.tables) + (before.indices - after.indices)

    /** The migration targets from 3 up for which [committed] says there is no schema. */
    private fun uncommittedTargets(committed: (Int) -> Boolean): List<Int> =
        hops.map { it.to }.filter { it >= 3 && !committed(it) }

    /** Source with block comments and line comments removed — claims hold against code, not prose. */
    private fun strip(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    /**
     * Just enough JSON to read Room's schema export: objects, arrays, strings with their escapes,
     * numbers, `true`, `false` and `null`. Anything else throws rather than being skipped.
     */
    private class JsonReader(private val text: String) {
        private var at = 0

        fun read(): Any? {
            val value = value()
            space()
            if (at != text.length) throw IllegalStateException("trailing text at $at")
            return value
        }

        private fun space() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun value(): Any? {
            space()
            return when (text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        private fun word(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw IllegalStateException("bad literal at $at")
            at += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            at++
            space()
            if (text[at] == '}') { at++; return map }
            while (true) {
                space()
                val key = string()
                space()
                if (text[at++] != ':') throw IllegalStateException("expected ':' at ${at - 1}")
                map[key] = value()
                space()
                when (text[at++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalStateException("bad object at ${at - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            at++
            space()
            if (text[at] == ']') { at++; return list }
            while (true) {
                list += value()
                space()
                when (text[at++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalStateException("bad array at ${at - 1}")
                }
            }
        }

        private fun string(): String {
            if (text[at++] != '"') throw IllegalStateException("expected a string at ${at - 1}")
            val out = StringBuilder()
            while (true) {
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val escaped = text[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> { out.append(text.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                        '"', '\\', '/' -> out.append(escaped)
                        else -> throw IllegalStateException("bad escape at ${at - 1}")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            if (at == start) throw IllegalStateException("unexpected '${text[at]}' at $at")
            return text.substring(start, at).toDouble()
        }
    }
}
