package com.daymark.companion.storage

import com.daymark.companion.storage.KeyDocumentException.Kind
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
        assertEquals(1L, first.create(doc("one")))
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
        refusal(Kind.EXISTS) { second.create(doc("again")) }
        refusal(Kind.NOT_NEXT) { second.append(2, doc("stale")) }
        assertEquals(3L, second.append(3, doc("three")), "the next version is still taken")
        // Never deleted, never overwritten.
        assertContentEquals(keyparams, File(dir, KeyDocumentStore.KEYPARAMS_FILE).readBytes())
    }

    @Test
    fun `the version after the last one kept is refused as full, and a version out of turn as not next`() {
        val store = open(maxVersions = 3)
        store.create(doc("one"))
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
    fun `a volume that refuses a read or a write is the store's refusal, and leaves no temp file`() {
        val store = open()
        // The positive control: the same calls succeed while the volume takes them.
        store.putKeyparams(keyparams)
        assertContentEquals(keyparams, store.getKeyparams())

        // A non-empty directory where the file goes. Unlike permission bits, root cannot write through it.
        val file = File(dir, KeyDocumentStore.KEYPARAMS_FILE)
        assertTrue(file.delete() && file.mkdir() && File(file, "occupied").createNewFile(), "could not put a directory in the file's place")
        refusal(Kind.IO) { store.putKeyparams(keyparams) }
        assertEquals(emptyList(), File(dir, "tmp").list()!!.toList(), "the failed write left its temp file")
        refusal(Kind.IO) { store.getKeyparams() }
        refusal(Kind.IO) { store.current() }

        // The database's failures too: a closed connection is an SQLException inside the store.
        store.close()
        refusal(Kind.IO) { store.create(doc("one")) }
        refusal(Kind.IO) { store.current() }
    }
}
