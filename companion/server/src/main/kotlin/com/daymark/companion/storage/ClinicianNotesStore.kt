package com.daymark.companion.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A clinician's own notebook, one opaque blob per relationship (COMPANION_THERAPIST.md §9d,
 * DECISIONS.md §D12).
 *
 * WHAT THE SERVER HOLDS. Ciphertext the clinician's browser sealed under a key derived from their own
 * box key, and a version number. The server cannot open it, never parses it, and logs nothing about
 * it. Only the clinician's session reaches it: there is no owner route, so the person the notes are
 * about can neither read them nor learn their size.
 *
 * WHY IT IS NOT A RELATIONSHIP CHANNEL. Every [Channel] is readable by both counterparties, ends
 * after [RelationStore.ITEM_LIFETIME_MS] and is swept, and the notes must do none of those things:
 * they are the clinician's records, and they stay while the relationship lasts, whatever the owner
 * shares or withdraws. The ending removes them ([delete]), because nobody can sign in to read them
 * after it.
 *
 * ONE BLOB, COMPARE-AND-SWAP. A write names the version it was based on and is refused with
 * [Outcome.Conflict] if another write landed first, so two open tabs cannot silently overwrite each
 * other's notes. The file is `version(8 bytes) || ciphertext`, written to a temporary file and moved
 * into place atomically, so a crash leaves the old notebook or the new one and never half of either.
 */
class ClinicianNotesStore(dataDir: String, val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val dir: Path = Path.of(dataDir, "clinician-notes")
    private val lock = Any()

    init {
        Files.createDirectories(dir)
    }

    sealed class Outcome {
        data class Written(val version: Long) : Outcome()
        data class Conflict(val current: Long) : Outcome()
        object TooLarge : Outcome()
        object DiskFull : Outcome()
    }

    data class Stored(val version: Long, val bytes: ByteArray)

    private fun fileFor(relRef: String): Path {
        require(SAFE.matches(relRef)) { "relRef is not a storable name" }
        return dir.resolve(relRef)
    }

    fun read(relRef: String): Stored? = synchronized(lock) {
        val f = fileFor(relRef)
        if (!Files.exists(f)) return null
        val all = Files.readAllBytes(f)
        if (all.size < HEADER) return null
        Stored(ByteBuffer.wrap(all, 0, HEADER).long, all.copyOfRange(HEADER, all.size))
    }

    fun write(relRef: String, basedOn: Long, bytes: ByteArray): Outcome = synchronized(lock) {
        if (bytes.size > maxBytes) return Outcome.TooLarge
        val current = read(relRef)?.version ?: 0L
        if (basedOn != current) return Outcome.Conflict(current)
        val next = current + 1
        val target = fileFor(relRef)
        var tmp: Path? = null
        try {
            tmp = Files.createTempFile(dir, "put", ".tmp")
            Files.write(tmp, ByteBuffer.allocate(HEADER).putLong(next).array() + bytes)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            tmp = null
            Outcome.Written(next)
        } catch (e: IOException) {
            Outcome.DiskFull
        } finally {
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** Removes the notebook. True when there was one. */
    fun delete(relRef: String): Boolean = synchronized(lock) {
        Files.deleteIfExists(fileFor(relRef))
    }

    companion object {
        const val HEADER = 8

        /** Far beyond years of typed notes; small enough that one relationship cannot fill a disk. */
        const val DEFAULT_MAX_BYTES: Long = 4L * 1024 * 1024

        /** A relRef is base64url of a 32-byte digest; nothing else may become a file name. */
        private val SAFE = Regex("^[A-Za-z0-9_-]{16,128}$")
    }
}
