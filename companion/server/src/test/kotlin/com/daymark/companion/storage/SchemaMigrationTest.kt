package com.daymark.companion.storage

import com.daymark.companion.StartupRefusal
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.PairingStore
import com.daymark.companion.mail.OwnerAccountStore
import com.daymark.companion.org.OrgStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upgrading can never leave a database half-changed (#193).
 *
 * Each database records the version of its structure; a change is preceded by a consistent copy in
 * `_pre-migrate/`, and is one transaction with the version it records; a change that fails leaves the
 * file byte for byte as it was and refuses the start; a database written before versions were kept is
 * adopted, never taken for empty; one newer than the release is refused; a second start changes
 * nothing; and no change touches an audit row.
 *
 * Every release so far is version 1, so the step from one version to the next is exercised by adding
 * a version to a real schema here ([plus]) — made of the same three kinds of change a real version is.
 */
class SchemaMigrationTest {

    private val at: Instant = Instant.parse("2026-09-26T14:45:12Z")
    private val clock = { at }

    private fun tmpDir(): Path = Files.createTempDirectory("schema-migration")

    /** [this] with one more version, of [changes]: a release after this one, for the test. */
    private fun Schema.plus(vararg changes: SchemaChange) = Schema(file, versions + listOf(changes.toList()))

    private fun connect(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

    private fun userVersion(file: Path): Int = connect(file).use { Schema.userVersion(it) }

    private fun stamp(file: Path, version: Int) = connect(file).use { c ->
        c.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    private fun exec(file: Path, vararg sql: String) = connect(file).use { c ->
        c.createStatement().use { st -> sql.forEach { st.execute(it) } }
    }

    private fun columns(file: Path, table: String): List<String> = connect(file).use { c ->
        c.prepareStatement("SELECT name FROM pragma_table_info(?)").use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    private fun hasIndex(file: Path, name: String): Boolean = connect(file).use { c ->
        c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { it.next() }
        }
    }

    /** Each table of [file] and its columns, as they are now: what [contents] reads a database by. */
    private fun layout(file: Path): Map<String, List<String>> = connect(file).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name")
                .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }.associateWith { columns(file, it) }

    /**
     * Every row of every table in [layout], over [layout]'s columns, with its rowid, in rowid order:
     * a row that was rewritten, removed, renumbered or re-ordered reads differently here.
     */
    private fun contents(file: Path, layout: Map<String, List<String>>): Map<String, List<List<Any?>>> =
        connect(file).use { c ->
            layout.mapValues { (table, cols) ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT rowid, ${cols.joinToString()} FROM $table ORDER BY rowid").use { rs ->
                        buildList {
                            while (rs.next()) {
                                add((1..cols.size + 1).map { i -> rs.getObject(i).let { v -> if (v is ByteArray) v.toList() else v } })
                            }
                        }
                    }
                }
            }
        }

    /** The copies in [dir]'s `_pre-migrate/`, by name; none when it does not exist. */
    private fun copies(dir: Path): List<String> {
        val pre = dir.resolve(Schema.PRE_MIGRATE_DIR)
        return if (pre.exists()) pre.listDirectoryEntries().map { it.name }.sorted() else emptyList()
    }

    /** A copy to look into, so that looking never adds a file to `_pre-migrate/` or changes the copy. */
    private fun lookInto(copy: Path): Path =
        Files.createTempDirectory("schema-copy").resolve(copy.name).also { Files.copy(copy, it) }

    /** An auth.db at version 1 holding invites and a key registration, written by the real store. */
    private fun authAtVersion1(dir: Path): Path {
        AuthStore(dir.toString()).use { auth ->
            auth.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)
            auth.mintInvite("relB", listOf("read.share"), ttlSeconds = 86_400L)
            auth.registerTherapistKeys("relA", "box-pub", "sign-pub")
        }
        return dir.resolve("auth.db").also { assertEquals(1, userVersion(it), "the fixture is at version 1") }
    }

    // ---- A version to the next -----------------------------------------------------------------

    @Test
    fun `a database at the previous version is copied to _pre-migrate, then brought to the current one`() {
        val dir = tmpDir()
        val db = authAtVersion1(dir)
        val layout = layout(db)
        val before = contents(db, layout)
        assertEquals(2, before.getValue("invites").size, "the fixture holds rows, so the copy has something to hold")

        val next = AuthStore.SCHEMA.plus(
            SchemaChange.Column("invites", "note", "TEXT"),
            SchemaChange.Index("CREATE INDEX IF NOT EXISTS idx_invites_status ON invites(status)"),
        )
        next.open(dir, clock).close()

        assertEquals(2, userVersion(db))
        assertTrue("note" in columns(db, "invites") && hasIndex(db, "idx_invites_status"), "the change was made")
        assertEquals(before, contents(db, layout), "and every row is as it was")

        assertEquals(listOf("auth.v1.20260926T144512Z.db"), copies(dir), "one copy, named for the database, the version it held and the time")
        val copy = lookInto(dir.resolve(Schema.PRE_MIGRATE_DIR).resolve("auth.v1.20260926T144512Z.db"))
        assertEquals(1, userVersion(copy), "the copy is the database as it was before the change")
        assertFalse("note" in columns(copy, "invites"))
        assertEquals(before, contents(copy, layout), "and it opens and holds every row it held")
    }

    @Test
    fun `a change that fails partway leaves the file byte for byte as it was, removes its copy, and refuses the start`() {
        // Control first, on the same fixture: the change without its failing half does alter the
        // bytes, so an unchanged checksum below is the rollback and not a blind hash.
        val controlDir = tmpDir()
        val control = authAtVersion1(controlDir)
        val unchanged = sha256(control)
        AuthStore.SCHEMA.plus(SchemaChange.Column("invites", "note", "TEXT")).open(controlDir, clock).close()
        assertNotEquals(unchanged, sha256(control), "control: the first half of the change alone alters the file")

        // WAL, as every database the server writes is; and the rollback journal, as a copy put back
        // from somewhere else may be. Nothing may reach the file before the change's transaction in either.
        for (mode in listOf("WAL", "DELETE")) {
            val dir = tmpDir()
            val db = authAtVersion1(dir)
            exec(db, "PRAGMA journal_mode=$mode")
            // A NOT NULL column with no default cannot be added to a table that has rows, and invites has
            // two, so the second change fails after the first has been made.
            assertTrue(contents(db, layout(db)).getValue("invites").isNotEmpty(), "the table has rows, which is what makes the second change fail")
            val original = sha256(db)
            val failing = AuthStore.SCHEMA.plus(
                SchemaChange.Column("invites", "note", "TEXT"),
                SchemaChange.Column("invites", "must", "INTEGER NOT NULL"),
            )

            val refusal = assertFailsWith<StartupRefusal>(mode) { failing.open(dir, clock) }

            assertEquals(
                "Refusing to start: auth.db could not be changed from structure version 1 to 2 (SQLITE_ERROR); " +
                    "the change was rolled back and the file is as it was (COMPANION_DEPLOYMENT.md §7).",
                refusal.message,
                mode,
            )
            assertEquals(original, sha256(db), "$mode: byte for byte as it was, the column added first gone with the rollback")
            assertEquals(1, userVersion(db), mode)
            assertFalse("note" in columns(db, "invites"), mode)
            assertEquals(emptyList(), copies(dir), "$mode: the copy taken first is removed, since the file it copied is untouched")
            assertTrue(dir.resolve(Schema.PRE_MIGRATE_DIR).exists(), "$mode control: a copy was taken, in the directory it was removed from")
        }
    }

    @Test
    fun `no change is made when the copy that comes first cannot be written`() {
        val dir = tmpDir()
        val db = authAtVersion1(dir)
        val original = sha256(db)
        // A file where the copies' directory goes, so the copy cannot be written whoever runs the server.
        Files.write(dir.resolve(Schema.PRE_MIGRATE_DIR), byteArrayOf())
        val next = AuthStore.SCHEMA.plus(SchemaChange.Column("invites", "note", "TEXT"))

        val refusal = assertFailsWith<StartupRefusal> { next.open(dir, clock) }

        assertEquals(
            "Refusing to start: auth.db needs its structure changed from version 1 to 2, and the copy that comes " +
                "first could not be written to _pre-migrate/ (FileAlreadyExistsException); nothing was changed " +
                "(COMPANION_DEPLOYMENT.md §7).",
            refusal.message,
        )
        assertEquals(original, sha256(db), "no copy, no change")
        assertEquals(1, userVersion(db))
        assertFalse("note" in columns(db, "invites"))

        // Control: with the way to the copies clear, the same change is made, after its copy.
        Files.delete(dir.resolve(Schema.PRE_MIGRATE_DIR))
        next.open(dir, clock).close()
        assertEquals(2, userVersion(db))
        assertEquals(listOf("auth.v1.20260926T144512Z.db"), copies(dir))
    }

    // ---- Databases from before versions were kept ----------------------------------------------

    @Test
    fun `a database written before versions were kept, in today's structure, is marked version 1 with every row kept and nothing copied`() {
        val dir = tmpDir()
        val db = authAtVersion1(dir)
        stamp(db, 0) // what the release before this one wrote: the same statements, and no version
        assertEquals(0, userVersion(db))
        val layout = layout(db)
        val before = contents(db, layout)
        assertTrue(before.getValue("invites").size == 2 && before.getValue("therapist_keys").size == 1, "the fixture holds rows")

        AuthStore(dir.toString()).use { auth ->
            assertNotNull(auth.therapistKeys("relA"), "the store reads what was there")
        }

        assertEquals(1, userVersion(db))
        assertEquals(layout, layout(db), "no table or column was added: it already had them all")
        assertEquals(before, contents(db, layout), "and no row was lost or changed")
        assertEquals(emptyList(), copies(dir), "nothing about its structure changed, so nothing was copied")
    }

    @Test
    fun `a database of an older shape, written before versions were kept, is copied, gains today's columns, and keeps every row`() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L

        // auth.db as the first release wrote it: no last_used_step, and none of the later tables.
        val authDir = tmpDir()
        val auth = authDir.resolve("auth.db")
        exec(
            auth,
            "PRAGMA journal_mode=WAL",
            "CREATE TABLE invites (invite_id TEXT NOT NULL PRIMARY KEY, rel_ref TEXT NOT NULL, scope TEXT NOT NULL, " +
                "secret_argon2 TEXT NOT NULL, ttl_expiry INTEGER NOT NULL, status TEXT NOT NULL, " +
                "fail_count INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
            "CREATE TABLE totp (credential_id TEXT NOT NULL PRIMARY KEY, rel_ref TEXT NOT NULL, secret_b64 TEXT NOT NULL, " +
                "fail_count INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX idx_totp_rel_ref ON totp(rel_ref)",
            "CREATE TABLE enroll_tickets (ticket_hash TEXT NOT NULL PRIMARY KEY, invite_id TEXT NOT NULL, " +
                "rel_ref TEXT NOT NULL, scope TEXT NOT NULL, expiry INTEGER NOT NULL)",
            "CREATE TABLE sessions (session_id_hash TEXT NOT NULL PRIMARY KEY, credential_id TEXT NOT NULL, " +
                "rel_ref TEXT NOT NULL, csrf_token TEXT NOT NULL, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, " +
                "absolute_expiry INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)",
            "INSERT INTO invites VALUES ('inv-1', 'relA', 'read.share', 'argon', $now, 'CONSUMED', 0, 0, ${now - day})",
            "INSERT INTO totp VALUES ('cred-1', 'relA', 'c2VlZA', 0, 0, ${now - day})",
            "INSERT INTO sessions VALUES ('sess-hash', 'cred-1', 'relA', 'csrf', ${now - day}, ${now - day}, ${now + day}, 0)",
        )

        // pairing.db as the relay first wrote it: no envelope columns.
        val pairingDir = tmpDir()
        val pairing = pairingDir.resolve("pairing.db")
        exec(
            pairing,
            "PRAGMA journal_mode=WAL",
            "CREATE TABLE pairing_exchanges (exchange_id TEXT PRIMARY KEY, invite_id TEXT NOT NULL, rel_ref TEXT NOT NULL, " +
                "sid TEXT NOT NULL, msg_a TEXT NOT NULL, msg_b TEXT, state TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "responded_at INTEGER, expiry INTEGER NOT NULL)",
            "CREATE INDEX idx_pairing_invite ON pairing_exchanges(invite_id, created_at)",
            "INSERT INTO pairing_exchanges VALUES ('ex-1', 'inv-1', 'relA', 'sid', 'msg-a', 'msg-b', 'RESPONDED', ${now - day}, ${now - day + 1}, ${now + day})",
        )

        // rel-index.db as the first release wrote it: no expiry, revoked or held, and a stored item.
        val relDir = tmpDir()
        val rel = relDir.resolve("rel-index.db")
        exec(
            rel,
            "PRAGMA journal_mode=WAL",
            "CREATE TABLE rel_blobs (rel_ref TEXT NOT NULL, channel TEXT NOT NULL, lineage TEXT NOT NULL, " +
                "version INTEGER NOT NULL, size INTEGER NOT NULL, content_hash TEXT NOT NULL, setting_key TEXT, " +
                "created_at INTEGER NOT NULL, PRIMARY KEY (rel_ref, channel, lineage, version))",
            "INSERT INTO rel_blobs VALUES ('relA', 'assignments', 'task', 1, 3, 'h', NULL, ${now - day})",
        )
        val blob = relDir.resolve("rel/relA/assignments/task/1.blob")
        Files.createDirectories(blob.parent)
        Files.write(blob, byteArrayOf(7, 8, 9))

        val cases = listOf(
            Triple(authDir, auth, listOf("totp" to "last_used_step")),
            Triple(pairingDir, pairing, listOf("pairing_exchanges" to "env_to_owner", "pairing_exchanges" to "env_to_therapist")),
            Triple(relDir, rel, listOf("rel_blobs" to "expiry", "rel_blobs" to "revoked", "rel_blobs" to "held")),
        )
        val layouts = cases.associate { (_, db, _) -> db to layout(db) }
        val befores = cases.associate { (_, db, _) -> db to contents(db, layouts.getValue(db)) }
        for ((_, db, added) in cases) {
            assertEquals(0, userVersion(db))
            assertTrue(added.none { (t, c) -> c in columns(db, t) }, "${db.name}: the fixture lacks what version 1 adds")
            assertTrue(befores.getValue(db).values.any { it.isNotEmpty() }, "${db.name}: and holds rows")
        }

        AuthStore(authDir.toString()).use { store -> assertNotNull(store.getTotp("cred-1"), "the credential is still there") }
        PairingStore(pairingDir.toString()).use { store ->
            val exchange = assertNotNull(store.latestExchangeFor("inv-1"))
            assertEquals("msg-b", exchange.msgBB64)
            assertNull(exchange.envToOwnerB64, "a reply from before the column reads back with no envelope")
        }
        RelationStore(relDir.toString(), 1_000_000, 50, 10_000_000, clock = { now }).use { store ->
            assertContentEquals(byteArrayOf(7, 8, 9), store.fetch("relA", Channel.ASSIGNMENTS, "task", 1), "the item is served")
        }

        for ((dir, db, added) in cases) {
            val name = db.name
            assertEquals(1, userVersion(db), "$name is marked version 1")
            assertTrue(added.all { (t, c) -> c in columns(db, t) }, "$name gained ${added.map { it.second }}")
            assertEquals(befores.getValue(db), contents(db, layouts.getValue(db)), "$name kept every row, unchanged")

            val stem = name.removeSuffix(".db")
            val copyName = copies(dir).single()
            assertTrue(Regex("""^\Q$stem\E\.v0\.\d{8}T\d{6}Z\.db$""").matches(copyName), "$name was copied first: $copyName")
            val copy = lookInto(dir.resolve(Schema.PRE_MIGRATE_DIR).resolve(copyName))
            assertEquals(0, userVersion(copy), "the copy of $name is the database as it was")
            assertTrue(added.none { (t, c) -> c in columns(copy, t) })
            assertEquals(befores.getValue(db), contents(copy, layouts.getValue(db)), "and holds its rows")
        }
        val relRows = connect(rel).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT expiry, revoked, held FROM rel_blobs").use { rs ->
                    rs.next()
                    Triple(rs.getObject(1), rs.getInt(2), rs.getInt(3))
                }
            }
        }
        assertEquals(Triple(null, 0, 1), relRows, "an older row reads no end, not withdrawn, and held")
        assertEquals(0, connect(auth).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT last_used_step FROM totp").use { rs -> rs.next(); rs.getInt(1) } } })
        assertContentEquals(byteArrayOf(7, 8, 9), Files.readAllBytes(blob), "no blob file is touched")
    }

    @Test
    fun `a database its changes cannot complete is refused, left as it was, and never marked`() {
        // owner_token without bootstrap_token. No release wrote this — the column is in the table's own
        // statement, so no version adds it — and marking it version 1 would promise a column it lacks.
        val dir = tmpDir()
        val db = dir.resolve("owner-account.db")
        exec(
            db,
            "PRAGMA journal_mode=WAL",
            "CREATE TABLE owner_token (id INTEGER PRIMARY KEY, token TEXT, updated_at INTEGER)",
            "INSERT INTO owner_token VALUES (1, 'digest', 0)",
        )
        val before = sha256(db)

        val refusal = assertFailsWith<StartupRefusal> { OwnerAccountStore(dir.toString(), envToken = "owner-token") }

        assertEquals(
            "Refusing to start: owner-account.db could not be changed from structure version 0 to 1 (it would still " +
                "lack owner_token.bootstrap_token); the change was rolled back and the file is as it was " +
                "(COMPANION_DEPLOYMENT.md §7).",
            refusal.message,
        )
        assertEquals(before, sha256(db), "the tables made before the check went with the rollback")
        assertEquals(0, userVersion(db))
        assertEquals(emptyList(), copies(dir))

        // Control: the same database with the column is adopted.
        val controlDir = tmpDir()
        exec(
            controlDir.resolve("owner-account.db"),
            "PRAGMA journal_mode=WAL",
            "CREATE TABLE owner_token (id INTEGER PRIMARY KEY, token TEXT, bootstrap_token TEXT, updated_at INTEGER)",
            "INSERT INTO owner_token VALUES (1, 'digest', 'digest', 0)",
        )
        OwnerAccountStore(controlDir.toString(), envToken = "owner-token").close()
        assertEquals(1, userVersion(controlDir.resolve("owner-account.db")))
    }

    // ---- The ordinary start ---------------------------------------------------------------------

    @Test
    fun `a second start after a change changes nothing, neither a copy nor a byte of the database`() {
        val dir = tmpDir()
        val db = dir.resolve("pairing.db")
        exec(
            db,
            "PRAGMA journal_mode=WAL",
            "CREATE TABLE pairing_exchanges (exchange_id TEXT PRIMARY KEY, invite_id TEXT NOT NULL, rel_ref TEXT NOT NULL, " +
                "sid TEXT NOT NULL, msg_a TEXT NOT NULL, msg_b TEXT, state TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "responded_at INTEGER, expiry INTEGER NOT NULL)",
            "INSERT INTO pairing_exchanges VALUES ('ex-1', 'inv-1', 'relA', 'sid', 'msg-a', NULL, 'OPEN', 1, NULL, 2)",
        )
        val unversioned = sha256(db)

        PairingStore(dir.toString()).close()
        val afterFirst = sha256(db)
        val firstCopies = copies(dir)
        assertNotEquals(unversioned, afterFirst, "control: the first start changed the file")
        assertEquals(1, firstCopies.size, "control: and copied it first")
        val copyBytes = sha256(dir.resolve(Schema.PRE_MIGRATE_DIR).resolve(firstCopies.single()))

        PairingStore(dir.toString()).close()

        assertEquals(afterFirst, sha256(db), "the second start wrote nothing")
        assertEquals(firstCopies, copies(dir), "and copied nothing")
        assertEquals(copyBytes, sha256(dir.resolve(Schema.PRE_MIGRATE_DIR).resolve(firstCopies.single())), "and left the copy alone")
        assertEquals(1, userVersion(db))
    }

    @Test
    fun `a database at the current version that lacks part of it is refused, not repaired`() {
        // rel-index.db marked version 1 without the held column: changed outside the server, or put back
        // from a mismatched copy. Version 1 could add the column; a database that says it has it must not
        // be believed and quietly patched.
        val dir = tmpDir()
        val db = dir.resolve("rel-index.db")
        val legacy = arrayOf(
            "PRAGMA journal_mode=WAL",
            "CREATE TABLE rel_blobs (rel_ref TEXT NOT NULL, channel TEXT NOT NULL, lineage TEXT NOT NULL, " +
                "version INTEGER NOT NULL, size INTEGER NOT NULL, content_hash TEXT NOT NULL, setting_key TEXT, " +
                "created_at INTEGER NOT NULL, expiry INTEGER, revoked INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (rel_ref, channel, lineage, version))",
        )
        exec(db, *legacy, "PRAGMA user_version = 1")
        val before = sha256(db)

        val refusal = assertFailsWith<StartupRefusal> { RelationStore(dir.toString(), 1_000_000, 50, 10_000_000) }

        assertEquals(
            "Refusing to start: rel-index.db is at structure version 1 but lacks rel_blobs.held; nothing was changed " +
                "(COMPANION_DEPLOYMENT.md §7).",
            refusal.message,
        )
        assertEquals(before, sha256(db))
        assertFalse("held" in columns(db, "rel_blobs"))

        // Control: the same structure with no version recorded is adopted, and gains the column.
        val controlDir = tmpDir()
        exec(controlDir.resolve("rel-index.db"), *legacy)
        RelationStore(controlDir.toString(), 1_000_000, 50, 10_000_000).close()
        assertTrue("held" in columns(controlDir.resolve("rel-index.db"), "rel_blobs"))
    }

    // ---- A release rolled back past the one that changed a database ----------------------------

    @Test
    fun `a database stamped newer than this release is refused and left as it was, and nothing is copied`() {
        val dir = tmpDir()
        val db = authAtVersion1(dir)
        val newer = AuthStore.SCHEMA.current + 1
        stamp(db, newer)
        val before = sha256(db)

        val refusal = assertFailsWith<StartupRefusal> { AuthStore(dir.toString()) }

        assertEquals(
            "Refusing to start: auth.db is at structure version $newer, newer than version ${AuthStore.SCHEMA.current}, " +
                "the newest this release knows; nothing was changed. Run a release that knows version $newer, or put " +
                "back the copy that _pre-migrate/ holds from before it was changed (COMPANION_DEPLOYMENT.md §7).",
            refusal.message,
        )
        assertEquals(before, sha256(db), "not opened for anything but its version")
        assertFalse(dir.resolve(Schema.PRE_MIGRATE_DIR).exists())

        // Control: the same file at the version this release knows opens, rows and all.
        stamp(db, AuthStore.SCHEMA.current)
        AuthStore(dir.toString()).use { assertNotNull(it.therapistKeys("relA")) }
    }

    @Test
    fun `a database recording a version below 0 is refused and left as it was`() {
        // No release writes one; something other than this server did, and it is not this server's to read.
        val dir = tmpDir()
        val db = authAtVersion1(dir)
        stamp(db, -1)
        val before = sha256(db)

        val refusal = assertFailsWith<StartupRefusal> { AuthStore(dir.toString()) }

        assertEquals(
            "Refusing to start: auth.db records structure version -1, which no release of this server writes; " +
                "nothing was changed (COMPANION_DEPLOYMENT.md §7).",
            refusal.message,
        )
        assertEquals(before, sha256(db))
        assertFalse(dir.resolve(Schema.PRE_MIGRATE_DIR).exists())

        // Control: the same file at a version this release knows opens.
        stamp(db, AuthStore.SCHEMA.current)
        AuthStore(dir.toString()).use { assertNotNull(it.therapistKeys("relA")) }
    }

    // ---- The audit chain -------------------------------------------------------------------------

    @Test
    fun `no audit row changes across a migration, in value, number or order`() {
        val dir = tmpDir()
        var now = 1_000L
        AuditStore(dir.toString(), retentionSeconds = 100, clock = { now }).use { audit ->
            repeat(3) { audit.append("relA", AuditActor.THERAPIST, AuditAction.SHARE_OPEN, objectRef = "lin:$it") }
            audit.append("relB", AuditActor.OWNER, AuditAction.SHARE_REVOKE, objectRef = "lin")
            audit.append("relB", AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS, meta = mapOf("credentialId" to "cred-1"))
            now = 1_200L
            // Retention prunes relA's first three, so the rows left do not start at rowid 1: any rebuild
            // of the table would renumber them, and this test would see it.
            audit.append("relA", AuditActor.OWNER, AuditAction.SHARE_REVOKE, objectRef = "lin")
            assertNull(audit.verifyChain("relB").firstBreakSeq, "the fixture's chains are whole")
        }
        val db = dir.resolve("audit.db")
        val layout = layout(db)
        val before = contents(db, layout)
        val rows = before.getValue("audit_events")
        assertEquals(3, rows.size)
        assertTrue((rows.first().first() as Number).toLong() > 1, "the rows do not start at rowid 1")

        // Control: an edit of one field of one row is visible to this comparison. The edit is derived
        // from the stored value, so it is a change whatever that value is.
        val controlDir = tmpDir()
        Files.copy(db, controlDir.resolve("audit.db"))
        val ts = (rows.last()[3] as Number).toLong()
        exec(controlDir.resolve("audit.db"), "UPDATE audit_events SET ts = ${ts + 1} WHERE ts = $ts")
        assertNotEquals(before, contents(controlDir.resolve("audit.db"), layout), "control: a changed row reads differently")

        // Adopted from before versions were kept, by the real store.
        stamp(db, 0)
        AuditStore(dir.toString()).use { audit ->
            assertNull(audit.verifyChain("relA").firstBreakSeq)
            assertNull(audit.verifyChain("relB").firstBreakSeq)
        }
        assertEquals(1, userVersion(db))
        assertEquals(before, contents(db, layout), "adoption left every audit row as it was")

        // And a version that adds a column and an index to the chain's own table.
        AuditStore.schema("audit.db").plus(
            SchemaChange.Column("audit_events", "note", "TEXT"),
            SchemaChange.Index("CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_events(ts)"),
        ).open(dir, clock).close()
        assertEquals(2, userVersion(db))
        assertTrue("note" in columns(db, "audit_events"))
        assertEquals(before, contents(db, layout), "the change left every audit row as it was: value, rowid and order")
    }

    // ---- Every database -----------------------------------------------------------------------------

    private class Database(val file: String, val current: Int, val open: (String) -> AutoCloseable)

    /** The nine databases a server can open, each through the store that opens it. */
    private val databases = listOf(
        Database("auth.db", AuthStore.SCHEMA.current) { AuthStore(it) },
        Database("pairing.db", PairingStore.SCHEMA.current) { PairingStore(it) },
        Database("owner-account.db", OwnerAccountStore.SCHEMA.current) { OwnerAccountStore(it, envToken = "owner-token") },
        Database("org.db", OrgStore.SCHEMA.current) { OrgStore(it) },
        Database("audit.db", AuditStore.VERSIONS.size) { AuditStore(it) },
        Database("org-audit.db", AuditStore.VERSIONS.size) { AuditStore(it, dbName = "org-audit.db") },
        Database("index.db", BlobStore.SCHEMA.current) { BlobStore(it, 1_000_000, 10, 10_000_000) },
        Database("rel-index.db", RelationStore.SCHEMA.current) { RelationStore(it, 1_000_000, 10, 10_000_000) },
        Database("wrapped-key.db", KeyDocumentStore.SCHEMA.current) { KeyDocumentStore(it) },
    )

    @Test
    fun `every database the server opens records its version, and refuses one newer than it knows`() {
        assertEquals(9, databases.map { it.file }.toSet().size)
        for (d in databases) {
            // A new one: created at the current version, with nothing to copy.
            val fresh = tmpDir()
            d.open(fresh.toString()).close()
            assertEquals(d.current, userVersion(fresh.resolve(d.file)), "${d.file} records its version")
            assertFalse(fresh.resolve(Schema.PRE_MIGRATE_DIR).exists(), "${d.file}: a new database is not copied")

            // One from a later release.
            val newer = tmpDir()
            val file = newer.resolve(d.file)
            exec(file, "PRAGMA journal_mode=WAL", "CREATE TABLE later (x INTEGER)", "PRAGMA user_version = ${d.current + 1}")
            val before = sha256(file)
            val refusal = assertFailsWith<StartupRefusal>(d.file) { d.open(newer.toString()) }
            assertTrue(
                refusal.message.startsWith("Refusing to start: ${d.file} is at structure version ${d.current + 1}, newer than"),
                refusal.message,
            )
            assertEquals(before, sha256(file), "${d.file} is left as it was")

            // Control: the same file with no version recorded is adopted, beside what it holds.
            stamp(file, 0)
            d.open(newer.toString()).close()
            assertEquals(d.current, userVersion(file), "control: ${d.file} opens when its version is not newer")
            assertTrue("later" in layout(file), "control: and keeps the table it had")
        }
    }

    @Test
    fun `every change of every schema knows whether it is there`() {
        val schemas = listOf(
            AuthStore.SCHEMA, PairingStore.SCHEMA, OwnerAccountStore.SCHEMA, OrgStore.SCHEMA, AuditStore.schema("audit.db"),
            BlobStore.SCHEMA, RelationStore.SCHEMA, KeyDocumentStore.SCHEMA,
        )
        for (schema in schemas) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { mem ->
                // Whether a change is there decides whether a copy is taken, so each must be able to
                // answer both ways.
                val changes = schema.versions.flatten()
                assertTrue(changes.none { it.isPresent(mem) }, "${schema.file}: on an empty database, nothing is there")
                changes.forEach { it.applyIfMissing(mem) }
                assertTrue(changes.all { it.isPresent(mem) }, "${schema.file}: once made, every change is there")
            }
        }
    }
}
