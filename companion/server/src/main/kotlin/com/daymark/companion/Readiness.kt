package com.daymark.companion

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Does the server's one writable path actually work?
 *
 * `/healthz` answers "the process is up and routing requests", which is not the same question and
 * never has been. This server breaks in ways that leave it perfectly able to answer HTTP 200 while
 * being unable to accept a single snapshot: the `/data` volume mounted read-only, the volume owned
 * by a UID the container is not, or the disk simply full. All three are silent — the app only finds
 * out at the moment a client tries to write, which is the moment it matters least.
 *
 * With no bundled reverse proxy any more, this is also the only signal an operator's proxy has, so
 * it needs to be worth reading.
 *
 * **What this does NOT cover, deliberately:** SQLite lock contention. Probing it would mean either
 * taking [com.daymark.companion.storage.BlobStore]'s lock — so a long write turns the health
 * endpoint into a hanging endpoint — or touching its connection from another thread, which
 * sqlite-jdbc does not permit. A wedged writer therefore still reports ready. That is a known gap,
 * not an oversight.
 *
 * @param dataDir the single writable path (`DAYMARK_DATA_DIR`).
 * @param ttlMs how long a result is reused. This endpoint is unauthenticated and it touches the
 *   disk, so an uncached version would be a write amplifier for anyone who can reach it: one GET
 *   per packet becomes one `write`+`fsync`+`delete` per packet. Five seconds keeps it at most one
 *   probe per interval no matter the request rate, and is far finer than the 30 s HEALTHCHECK.
 */
class Readiness(
    private val dataDir: String,
    private val ttlMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [reason] is null when ready; when not, it is for the LOG, never for the response body. */
    data class Result(val ready: Boolean, val reason: String?)

    private data class Cached(val at: Long, val result: Result)

    private val cache = AtomicReference<Cached?>(null)
    private val lastLogged = AtomicReference<String?>(null)

    fun check(): Result {
        val now = clock()
        cache.get()?.let { if (now - it.at < ttlMs) return it.result }
        val result = probe()
        cache.set(Cached(now, result))
        // Log edges only. A failing probe every 30 s for a week is noise that buries the moment it
        // started, and the recovery is the more useful line of the two.
        val key = result.reason ?: "ready"
        if (lastLogged.getAndSet(key) != key) {
            if (result.ready) log.info("readiness restored: {} is writable", dataDir)
            else log.error("NOT READY: {}", result.reason)
        }
        return result
    }

    private fun probe(): Result {
        val dir = File(dataDir)
        if (!dir.isDirectory) return Result(false, "$dataDir is not a directory")

        // A fixed name, not a random one: a probe that mints a new file per call is a probe that
        // orphans one every time it dies between create and delete.
        val probe = File(dir, ".readyz")
        return try {
            // Non-empty on purpose. Creating a zero-byte file can succeed on a disk with no space
            // left for data, which is exactly the state this is supposed to catch.
            probe.outputStream().use { out ->
                out.write(PAYLOAD)
                out.flush()
                out.fd.sync()   // reach the filesystem, not just the page cache
            }
            probe.delete()
            Result(true, null)
        } catch (e: IOException) {
            probe.delete()
            Result(false, "cannot write to $dataDir (${e.javaClass.simpleName}: ${e.message})")
        } catch (e: SecurityException) {
            Result(false, "cannot write to $dataDir (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger("com.daymark.companion.readiness")

        /** 4 KiB — one filesystem block, so a full disk fails the write rather than the metadata. */
        val PAYLOAD = ByteArray(4096)
    }
}
