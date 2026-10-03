package com.daymark.companion

import com.daymark.companion.storage.RelationStore
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("com.daymark.companion.housekeeping")

/**
 * THE SERVER'S SCHEDULED CHORES: each registered job runs once at start-up, then again each time its
 * interval has passed, until the application stops.
 *
 * A rule applied only when someone reads or writes never reaches the rows nobody touches, and those
 * are the ones most likely to be left lying around. Work that must happen whether or not anyone asks
 * registers here: the relationship sweep (#338), and the same shape serves audit-log retention (#165)
 * and expired invitations and enrolment tickets (#196). One scheduler for all of them means one
 * background thread, one start-up order and one stop.
 *
 * - THE START-UP RUN happens on the caller's thread, inside [start], so what ended while the server
 *   was down is dealt with before the application takes a request.
 * - INTERVALS are measured on [ticker], a monotonic millisecond count, never the wall clock: a clock
 *   set back an hour does not starve a job, and one set forward does not fire every job at once. A
 *   job that falls due runs within [pollMs] of it.
 * - TESTS DO NOT SLEEP: they pass their own ticker, move it, and call [runDue].
 * - ONE PASS AT A TIME: [runDue] holds the scheduler's lock for the whole pass, so no job ever runs
 *   twice at once, whether the start-up run, the background thread or a test called it.
 * - A JOB THAT THROWS is logged by name and exception class only — never the message, which can hold
 *   a file path with a relationship id in it (COMPANION_DEPLOYMENT.md §10) — and runs again at its
 *   next interval. It stops neither the other jobs nor the thread.
 * - [close] STOPS IT CLEANLY: it waits for a pass that is running to finish, and no job starts after
 *   it. The application calls it as it stops, before anything the jobs use is closed.
 */
class Housekeeping(
    private val ticker: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pollMs: Long = 60_000L,
) : AutoCloseable {

    private class Job(val name: String, val intervalMs: Long, val work: () -> Unit) {
        /** Null until the first run, so every job is due at start-up. */
        var nextAt: Long? = null
    }

    private val lock = Any()
    private val jobs = mutableListOf<Job>()
    private var started = false
    private var closed = false
    private var executor: ScheduledExecutorService? = null

    /** Register [work] to run at start-up and then every [intervalMs]. Every job registers before [start]. */
    fun every(name: String, intervalMs: Long, work: () -> Unit): Unit = synchronized(lock) {
        require(intervalMs > 0) { "a job's interval must be positive" }
        check(!started) { "every job registers before start()" }
        jobs += Job(name, intervalMs, work)
    }

    /** Run each job that is due, and return how many ran. After [close], none is. */
    fun runDue(): Int = synchronized(lock) {
        if (closed) return 0
        var ran = 0
        for (job in jobs) {
            val now = ticker()
            val due = job.nextAt
            if (due != null && now < due) continue
            try {
                job.work()
            } catch (e: Exception) {
                log.warn("housekeeping job failed (job={}): {}", job.name, e.javaClass.simpleName)
            }
            job.nextAt = now + job.intervalMs
            ran++
        }
        ran
    }

    /**
     * The start-up run, on this thread; then the background thread that runs each job as it falls
     * due. With no job registered there is nothing to run, and no thread is started.
     */
    fun start() {
        synchronized(lock) {
            check(!started) { "start() runs once" }
            started = true
        }
        runDue()
        synchronized(lock) {
            if (closed || jobs.isEmpty()) return
            val background = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "daymark-housekeeping").apply { isDaemon = true }
            }
            background.scheduleWithFixedDelay(::tick, pollMs, pollMs, TimeUnit.MILLISECONDS)
            executor = background
        }
    }

    /** One background poll. It never throws: a scheduled task that throws is never run again. */
    private fun tick() {
        try {
            runDue()
        } catch (t: Throwable) {
            log.error("housekeeping pass failed: {}", t.javaClass.simpleName)
        }
    }

    /** True once [close] has run. */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** True while the background thread is alive: after [start] with a job registered, until [close]. */
    val isRunningInBackground: Boolean get() = synchronized(lock) { executor?.isTerminated == false }

    override fun close() {
        // Taking the lock waits for a pass that is running to finish; once `closed` is set, no pass
        // starts again, whichever thread asks.
        val background = synchronized(lock) {
            closed = true
            executor
        } ?: return
        background.shutdown()
        if (!background.awaitTermination(30, TimeUnit.SECONDS)) background.shutdownNow()
    }
}

/** How often the relationship sweep runs after its start-up run (#338). */
const val RELATION_SWEEP_INTERVAL_MS: Long = 60L * 60 * 1000

/**
 * One pass of the relationship sweep (#338), and its one log line.
 *
 * COUNTS ONLY. The line never carries a relRef, channel, lineage or version, and no size: which
 * relationship had something end, and how much, are facts about people (COMPANION_DEPLOYMENT.md §10;
 * #160). INFO when stored copies were removed or found already gone; WARN when any would not delete,
 * which is the operator's cue to look at the volume; DEBUG when nothing had ended, so an idle server
 * does not write a line an hour.
 */
internal fun sweepRelationships(store: RelationStore) {
    val o = store.sweepEnded()
    val line = "relationship sweep: {} stored copies removed, {} already gone, {} could not be removed and are retried next sweep"
    when {
        o.notRemoved > 0 -> log.warn(line, o.removed, o.alreadyGone, o.notRemoved)
        o.removed > 0 || o.alreadyGone > 0 -> log.info(line, o.removed, o.alreadyGone, o.notRemoved)
        else -> log.debug("relationship sweep: nothing had ended")
    }
}
