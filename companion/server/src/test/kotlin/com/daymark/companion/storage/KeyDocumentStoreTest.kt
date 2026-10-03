package com.daymark.companion.storage

import com.daymark.companion.storage.KeyDocumentException.Kind
import com.daymark.companion.storage.KeyDocumentStore.Precondition
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The key-document store on its own (#258): what survives closing it, where its versions stop, and
 * what a failing volume looks like from outside it. The routes' contract is KeyDocumentRoutesTest.
 */
class KeyDocumentStoreTest {

    private val dir: File = Files.createTempDirectory("keydocs").toFile()
    private val opened = mutableListOf<KeyDocumentStore>()

    private fun open(maxVersions: Long = KeyDocumentStore.MAX_WRAPPED_KEY_VERSIONS): KeyDocumentStore =
        KeyDocumentStore(dir.absolutePath, maxVersions).also { opened += it }

    @AfterTest
    fun cleanup() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun doc(marker: String) = """{"v":1,"slots":[{"kind":"passphrase","saltB64":"$marker"}]}""".toByteArray()

    private val keyparams = """{"v":1,"alg":"xchacha20poly1305","saltB64":"AAECAwQFBgcICQoLDA0ODw"}""".toByteArray()

    private fun refusal(kind: Kind, block: () -> Unit) {
        val e = assertFailsWith<KeyDocumentException> { block() }
        assertEquals(kind, e.kind, "refused as ${e.kind}, not $kind")
    }

    @Test
    fun `a store closed and opened again reads the newest version and still refuses the key parameters`() {
        val first = open()
        first.putKeyparams(keyparams)
        assertEquals(1L, first.create(Precondition.KeyparamsMatch(KeyDocumentStore.etagOf(keyparams)), doc("one")))
        assertEquals(2L, first.append(2, doc("two")))
        first.close()

        // A new store over the same files: nothing it answers can come from the first one's memory.
        val second = open()
        val current = second.current()
        assertIs<KeyDocumentStore.Current.Wrapped>(current)
        assertEquals(2L, current.version)
        assertContentEquals(doc("two"), current.document)
        refusal(Kind.SUPERSEDED) { second.getKeyparams() }
        refusal(Kind.SUPERSEDED) { second.putKeyparams(keyparams) }
        refusal(Kind.PRECONDITION_FAILED) { second.create(Precondition.NoKeyDocument, doc("again")) }
        refusal(Kind.PRECONDITION_FAILED) { second.create(Precondition.KeyparamsMatch(KeyDocumentStore.etagOf(keyparams)), doc("again")) }
        refusal(Kind.NOT_NEXT) { second.append(2, doc("stale")) }
        assertEquals(3L, second.append(3, doc("three")), "the next version is still taken")
        // Never deleted, never overwritten.
        assertContentEquals(keyparams, File(dir, KeyDocumentStore.KEYPARAMS_FILE).readBytes())
    }

    @Test
    fun `the version after the last one kept is refused as full, and a version out of turn as not next`() {
        val store = open(maxVersions = 3)
        store.create(Precondition.NoKeyDocument, doc("one"))
        store.append(2, doc("two"))
        assertEquals(3L, store.append(3, doc("three")), "the last version kept is taken")
        refusal(Kind.FULL) { store.append(4, doc("four")) }
        refusal(Kind.NOT_NEXT) { store.append(5, doc("five")) }
        val current = store.current()
        assertIs<KeyDocumentStore.Current.Wrapped>(current)
        assertEquals(3L, current.version)
        assertContentEquals(doc("three"), current.document)
    }

    @Test
    fun `a volume that refuses a read or a write is the store's refusal, not the volume's exception`() {
        val store = open()
        // Something unreadable where the key parameters go: a directory. Unlike permission bits, root
        // cannot read through it either.
        val file = File(dir, KeyDocumentStore.KEYPARAMS_FILE)
        assertTrue(file.mkdir() && File(file, "occupied").createNewFile(), "could not put a directory in the file's place")
        refusal(Kind.IO) { store.getKeyparams() }
        refusal(Kind.IO) { store.current() }
        refusal(Kind.IO) { store.create(Precondition.KeyparamsMatch("\"compared\""), doc("one")) }

        // Nothing there, and a staging area that cannot stage: a file where its directory goes.
        val staging = File(dir, "tmp")
        assertTrue(file.deleteRecursively() && staging.deleteRecursively() && staging.createNewFile())
        refusal(Kind.IO) { store.putKeyparams(keyparams) }
        assertTrue(!file.exists(), "a failed write leaves nothing where the key parameters go")

        // The positive control: the same volume, repaired, takes the same write and reads it back.
        assertTrue(staging.delete() && staging.mkdir())
        store.putKeyparams(keyparams)
        assertContentEquals(keyparams, store.getKeyparams())
    }

    @Test
    fun `a database that refuses a read or a write is the store's refusal, not the driver's exception`() {
        // An empty store, so a first run's precondition holds whichever part of it is weighed first,
        // and the create reaches the database whatever order the store asks in.
        val store = open()
        store.close()
        // A closed connection is an SQLException inside the store.
        refusal(Kind.IO) { store.create(Precondition.NoKeyDocument, doc("one")) }
        refusal(Kind.IO) { store.current() }
        refusal(Kind.IO) { store.getKeyparams() }
        refusal(Kind.IO) { store.append(2, doc("two")) }
        // The positive control: a store over the same files, open, answers.
        assertIs<KeyDocumentStore.Current.None>(open().current())
    }
}
