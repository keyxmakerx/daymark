package com.daymark.companion.storage

import com.daymark.companion.StartupRefusal
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val log = LoggerFactory.getLogger("com.daymark.companion.schema")

/**
 * One of the server's databases, and what each version of its structure adds (#193).
 *
 * THE VERSION a database holds is its `PRAGMA user_version`: the field SQLite keeps in the file's
 * header for the application's own use. Setting it is part of the transaction that makes the change
 * it records, so no database is ever marked with a version whose change did not commit. A database
 * that has never recorded one reads 0 — a new, empty one, and every one written before versions were
 * kept.
 *
 * [versions] lists what each version adds: the first entry makes version 1, the next version 2, and
 * [current] is how many there are. Version 1 of every database is its structure as it stood when
 * versions began to be kept, made by the statements that made it before. Each change is made only
 * where it is missing, so version 1 is also what an earlier, unversioned shape of the database is
 * brought to: it gains what it lacks and keeps every row.
 *
 * A CHANGE ADDS A TABLE, A COLUMN OR AN INDEX, and there is no other kind: [SchemaChange] is sealed.
 * No version can rewrite, delete or re-order a row, so every audit row (insert-only and hash-chained)
 * and every row of a key table stays as it was written; and nothing here touches a file but the
 * database and its copy, so no version ever reads or rewrites a blob.
 *
 * [open] is how every store opens its database; it says the order of events.
 */
internal class Schema(
    /** The database's file name in the data directory, and the only name a log line or refusal gives it. */
    val file: String,
    val versions: List<List<SchemaChange>>,
) {
    init {
        require(versions.isNotEmpty() && versions.none { it.isEmpty() }) { "every version adds something" }
    }

    /** The version this release brings the database to. */
    val current: Int get() = versions.size

    /** What [current] consists of, built from nothing in memory: the yardstick [lacking] measures a file against. */
    internal val expected: Shape by lazy {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { mem ->
            versions.flatten().forEach { it.applyIfMissing(mem) }
            Shape.of(mem)
        }
    }

    /**
     * Open [file] in [dataDir] at [current], or refuse the start with a [StartupRefusal].
     *
     *  1. The version is read before anything is written. A database at a version newer than
     *     [current] — a release rolled back past the one that changed it — is refused as it was found.
     *  2. At [current], nothing changes: the database is only checked for everything [current] has,
     *     and refused if it lacks any of it, since then it was changed outside the server or put back
     *     from a mismatched copy. This is the ordinary start.
     *  3. Below [current], and when a change is about to be made to a database that has anything in
     *     it, a consistent copy of it goes to `_pre-migrate/` first ([copyBefore]). A database that
     *     already has every change it is due, such as one written before versions were kept whose
     *     structure is already version 1, is only marked, and no copy is taken: nothing about its
     *     structure changes.
     *  4. The changes, the check that the result has everything [current] has, and the new version
     *     are one transaction, and nothing reaches the file before it. If any of it fails, the
     *     transaction rolls back, the file is byte for byte as it was, the copy just taken is removed
     *     (the file itself is that copy), and the start is refused with one line naming the database
     *     and both versions — never a row, never a path.
     */
    fun open(dataDir: Path, clock: () -> Instant = Instant::now): Connection {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:${dataDir.resolve(file)}")
        try {
            bringToCurrent(conn, dataDir, clock)
        } catch (e: Throwable) {
            runCatching { conn.close() }
            throw e
        }
        return conn
    }

    private fun bringToCurrent(conn: Connection, dataDir: Path, clock: () -> Instant) {
        val found = userVersion(conn)
        if (found < 0) {
            throw StartupRefusal(
                "Refusing to start: $file records structure version $found, which no release of this server " +
                    "writes; nothing was changed ($SEE).",
            )
        }
        if (found > current) {
            throw StartupRefusal(
                "Refusing to start: $file is at structure version $found, newer than version $current, the " +
                    "newest this release knows; nothing was changed. Run a release that knows version $found, " +
                    "or put back the copy that $PRE_MIGRATE_DIR/ holds from before it was changed ($SEE).",
            )
        }
        if (found == current) {
            val lacking = lacking(conn)
            if (lacking.isNotEmpty()) {
                throw StartupRefusal(
                    "Refusing to start: $file is at structure version $found but lacks " +
                        "${lacking.joinToString()}; nothing was changed ($SEE).",
                )
            }
            exec(conn, "PRAGMA journal_mode=WAL")
            return
        }

        // A change, then; FULL so that it is on the disk before anything is served on it. Each store
        // sets its own level once this returns.
        exec(conn, "PRAGMA synchronous=FULL")
        val changes = versions.drop(found).flatten()
        val holdsSomething = holdsSomething(conn)
        val copy = if (holdsSomething && changes.any { !it.isPresent(conn) }) {
            try {
                copyBefore(conn, dataDir, found, clock())
            } catch (e: Exception) {
                throw StartupRefusal(
                    "Refusing to start: $file needs its structure changed from version $found to $current, and " +
                        "the copy that comes first could not be written to $PRE_MIGRATE_DIR/ (${reason(e)}); " +
                        "nothing was changed ($SEE).",
                )
            }
        } else {
            null
        }

        var committing = false
        try {
            exec(conn, "BEGIN IMMEDIATE")
            // The copy is of `found`; a database another process moved on meanwhile is not this one's to change.
            if (userVersion(conn) != found) throw Unchanged("another process changed it first")
            changes.forEach { it.applyIfMissing(conn) }
            val lacking = lacking(conn)
            if (lacking.isNotEmpty()) throw Unchanged("it would still lack ${lacking.joinToString()}")
            exec(conn, "PRAGMA user_version = $current")
            committing = true
            exec(conn, "COMMIT")
        } catch (e: Exception) {
            if (committing) {
                // The commit itself failed, so whether it landed is unknown, and the copy is kept.
                throw StartupRefusal(
                    "Refusing to start: $file could not be changed from structure version $found to $current " +
                        "(${reason(e)}), and whether the change was kept is unknown" +
                        (copy?.let { "; the copy taken before it is $PRE_MIGRATE_DIR/${it.fileName}" } ?: "") +
                        " ($SEE).",
                )
            }
            runCatching { exec(conn, "ROLLBACK") }
            copy?.let { runCatching { Files.deleteIfExists(it) } }
            throw StartupRefusal(
                "Refusing to start: $file could not be changed from structure version $found to $current " +
                    "(${reason(e)}); the change was rolled back and the file is as it was ($SEE).",
            )
        }
        // Every database runs in WAL. Switched only now, so a change that fails has written nothing to
        // the file before its transaction, whatever journal mode the file was in.
        exec(conn, "PRAGMA journal_mode=WAL")

        when {
            copy != null -> log.info(
                "{}: structure changed from version {} to {}; the copy taken before the change is {}/{}",
                file, found, current, PRE_MIGRATE_DIR, copy.fileName,
            )
            holdsSomething -> log.info("{}: recorded as structure version {}, which it already had", file, current)
            else -> log.debug("{}: created at structure version {}", file, current)
        }
    }

    /**
     * A consistent copy of the database as it stands, at `_pre-migrate/<name>.v<found>.<time>.db`, on
     * the disk before this returns.
     *
     * `VACUUM INTO` writes what one read transaction sees, WAL included, so a write in flight cannot
     * tear it the way a copy of the file could. It does not flush what it writes, so the copy is
     * written under a staging name, flushed, and only then given its own: a copy that has its name is
     * whole. The directory is flushed too, so the name is on the disk before the change it guards
     * against is. The name carries the time to the second, in UTC, and a number after it when two
     * copies of one version land in the same second.
     */
    private fun copyBefore(conn: Connection, dataDir: Path, found: Int, at: Instant): Path {
        val dir = Files.createDirectories(dataDir.resolve(PRE_MIGRATE_DIR))
        val stem = "${file.removeSuffix(".db")}.v$found.${COPY_TIME.format(at)}"
        val target = generateSequence(1) { it + 1 }
            .map { n -> dir.resolve(if (n == 1) "$stem.db" else "$stem-$n.db") }
            .first { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) && !Files.exists(staging(it), LinkOption.NOFOLLOW_LINKS) }
        val staging = staging(target)
        try {
            conn.prepareStatement("VACUUM INTO ?").use { ps ->
                ps.setString(1, staging.toString())
                ps.execute()
            }
            FileChannel.open(staging, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(staging) }
            throw e
        }
        // Not every platform can open a directory to flush it; the ones this ships on can.
        runCatching { FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
        return target
    }

    /** What [expected] has that the database does not: `table`, `table.column` or an index's name. */
    private fun lacking(conn: Connection): List<String> {
        val actual = Shape.of(conn)
        return expected.tables.flatMap { (table, columns) ->
            val has = actual.tables[table] ?: return@flatMap listOf(table)
            (columns - has).map { "$table.$it" }
        } + (expected.indexes - actual.indexes)
    }

    /** A reason that names what failed and carries nothing from the database: a result code or a class name. */
    private fun reason(e: Throwable): String = when (e) {
        is Unchanged -> e.message
        is SQLiteException -> e.resultCode.name
        else -> e.javaClass.simpleName
    }

    /** A failure the migration itself detects, whose message is already fit for the refusal line. */
    private class Unchanged(override val message: String) : Exception(message)

    internal companion object {
        /** Where copies go, in the data directory. The name `COMPANION_DEPLOYMENT.md` §6.1 gives it. */
        const val PRE_MIGRATE_DIR = "_pre-migrate"

        private const val SEE = "COMPANION_DEPLOYMENT.md §7"

        private val COPY_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private fun staging(copy: Path): Path = copy.resolveSibling("${copy.fileName}.partial")

        fun userVersion(conn: Connection): Int =
            conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

        /** Whether the database holds any table, index, view or trigger of its own; a new one holds none. */
        private fun holdsSomething(conn: Connection): Boolean =
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite\\_%' ESCAPE '\\'")
                    .use { rs -> rs.next(); rs.getInt(1) > 0 }
            }
    }
}

/** A database's tables with the names of their columns, and its indexes, leaving out SQLite's own. */
internal data class Shape(val tables: Map<String, Set<String>>, val indexes: Set<String>) {
    companion object {
        fun of(conn: Connection): Shape = Shape(
            tables = namesOf(conn, "table").associateWith { table ->
                conn.prepareStatement("SELECT name FROM pragma_table_info(?)").use { ps ->
                    ps.setString(1, table)
                    ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
                }
            },
            indexes = namesOf(conn, "index").toSet(),
        )

        private fun namesOf(conn: Connection, type: String): List<String> =
            conn.prepareStatement("SELECT name FROM sqlite_master WHERE type=? AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'").use { ps ->
                ps.setString(1, type)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
    }
}

/**
 * One thing a version adds. Each kind is made only where it is missing, which is what lets version 1
 * bring any earlier shape of a database up to it, and none of them can touch a row.
 */
internal sealed class SchemaChange {
    abstract fun isPresent(conn: Connection): Boolean

    abstract fun applyIfMissing(conn: Connection)

    /** A table, made by [ddl], which is `CREATE TABLE IF NOT EXISTS <name> (…)`. */
    class Table(private val ddl: String) : SchemaChange() {
        val name: String = requireNotNull(TABLE_DDL.find(ddl)?.groupValues?.get(1)) {
            "a table is made by CREATE TABLE IF NOT EXISTS <name> (…)"
        }

        override fun isPresent(conn: Connection): Boolean = hasObject(conn, "table", name)

        override fun applyIfMissing(conn: Connection) {
            if (!isPresent(conn)) exec(conn, ddl)
        }
    }

    /**
     * A column added to [table] by `ALTER TABLE … ADD COLUMN`. SQLite records it in the table's
     * definition and reads the default for the rows already there, so no row is rewritten.
     */
    class Column(val table: String, val name: String, private val declaration: String) : SchemaChange() {
        override fun isPresent(conn: Connection): Boolean =
            conn.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name=?").use { ps ->
                ps.setString(1, table)
                ps.setString(2, name)
                ps.executeQuery().use { it.next() }
            }

        override fun applyIfMissing(conn: Connection) {
            if (!isPresent(conn)) exec(conn, "ALTER TABLE $table ADD COLUMN $name $declaration")
        }
    }

    /** An index, made by [ddl], which is `CREATE [UNIQUE] INDEX IF NOT EXISTS <name> ON …`. */
    class Index(private val ddl: String) : SchemaChange() {
        val name: String = requireNotNull(INDEX_DDL.find(ddl)?.groupValues?.get(1)) {
            "an index is made by CREATE [UNIQUE] INDEX IF NOT EXISTS <name> ON …"
        }

        override fun isPresent(conn: Connection): Boolean = hasObject(conn, "index", name)

        override fun applyIfMissing(conn: Connection) {
            if (!isPresent(conn)) exec(conn, ddl)
        }
    }

    private companion object {
        val TABLE_DDL = Regex("""^\s*CREATE TABLE IF NOT EXISTS (\w+)\s*\(""")
        val INDEX_DDL = Regex("""^\s*CREATE (?:UNIQUE )?INDEX IF NOT EXISTS (\w+) ON """)

        fun hasObject(conn: Connection, type: String, name: String): Boolean =
            conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type=? AND name=?").use { ps ->
                ps.setString(1, type)
                ps.setString(2, name)
                ps.executeQuery().use { it.next() }
            }
    }
}

private fun exec(conn: Connection, sql: String) {
    conn.createStatement().use { it.execute(sql) }
}
