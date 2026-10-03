package com.daymark.companion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one scheduler the server's chores share (#338; #165 and #196 are meant to register here too).
 *
 * Nothing here sleeps: the ticker is a number the test moves, and [Housekeeping.runDue] is the pass
 * the background thread would make. The one test of the thread itself waits on a latch the job
 * releases, so it returns the moment the thread has done its work.
 */
class HousekeepingTest {

    private val hour = 60L * 60 * 1000
    private val ticks = AtomicLong(0)

    private val logger = LoggerFactory.getLogger("com.daymark.companion.housekeeping") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeTest
    fun attach() {
        appender.context = logger.loggerContext
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun chores(pollMs: Long = 60_000L) = Housekeeping(ticker = { ticks.get() }, pollMs = pollMs)

    @Test
    fun `every job runs once at start-up, before start returns`() {
        val hk = chores()
        var a = 0
        var b = 0
        hk.every("a", hour) { a++ }
        hk.every("b", 24 * hour) { b++ }
        hk.start()
        try {
            assertEquals(1, a)
            assertEquals(1, b)
        } finally {
            hk.close()
        }
    }

    @Test
    fun `a job runs again once its interval has passed, and not before`() {
        val hk = chores()
        var runs = 0
        hk.every("sweep", hour) { runs++ }
        hk.start()
        try {
            ticks.set(hour - 1)
            assertEquals(0, hk.runDue(), "one millisecond early")
            assertEquals(1, runs)
            ticks.set(hour)
            assertEquals(1, hk.runDue(), "due")
            assertEquals(2, runs)
            assertEquals(0, hk.runDue(), "and not twice for the same interval")
            ticks.set(2 * hour)
            hk.runDue()
            assertEquals(3, runs)
        } finally {
            hk.close()
        }
    }

    @Test
    fun `each job keeps its own interval`() {
        val hk = chores()
        var hourly = 0
        var daily = 0
        hk.every("hourly", hour) { hourly++ }
        hk.every("daily", 24 * hour) { daily++ }
        hk.start()
        try {
            for (h in 1..24) {
                ticks.set(h * hour)
                hk.runDue()
            }
            assertEquals(25, hourly)
            assertEquals(2, daily)
        } finally {
            hk.close()
        }
    }

    @Test
    fun `a job that throws is logged by name and class only, and stops neither the others nor its own next run`() {
        val hk = chores()
        var failing = 0
        var healthy = 0
        hk.every("failing", hour) {
            failing++
            throw IllegalStateException("/data/rel/SECRET-RELREF/shares/lineage/3.blob")
        }
        hk.every("healthy", hour) { healthy++ }
        hk.start()
        try {
            assertEquals(1, failing)
            assertEquals(1, healthy, "the job after the failing one still ran")
            ticks.set(hour)
            hk.runDue()
            assertEquals(2, failing, "and the failing job is tried again at its next interval")
            assertEquals(2, healthy)
        } finally {
            hk.close()
        }
        val lines = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertEquals(2, lines.size, lines.toString())
        assertEquals("housekeeping job failed (job=failing): IllegalStateException", lines.first())
        // The exception's message never reaches the log, and neither does a stack trace.
        assertTrue(lines.none { it.contains("SECRET-RELREF") }, lines.toString())
        assertTrue(appender.list.all { it.throwableProxy == null })
    }

    @Test
    fun `close stops it cleanly, and nothing runs afterwards`() {
        val hk = chores()
        var runs = 0
        hk.every("sweep", hour) { runs++ }
        hk.start()
        assertTrue(hk.isRunningInBackground, "control: the background thread is running before close")
        hk.close()
        assertTrue(hk.isClosed)
        assertFalse(hk.isRunningInBackground, "the background thread has ended")
        ticks.set(10 * hour)
        assertEquals(0, hk.runDue())
        assertEquals(1, runs, "only the start-up run happened")
    }

    @Test
    fun `with no job registered, no thread is started`() {
        val hk = chores()
        hk.start()
        assertFalse(hk.isRunningInBackground)
        hk.close()
    }

    @Test
    fun `every job registers before start`() {
        val hk = chores()
        hk.start()
        assertFailsWith<IllegalStateException> { hk.every("late", hour) {} }
        hk.close()
    }

    @Test
    fun `the background thread runs a job when it falls due`() {
        val hk = chores(pollMs = 5)
        val secondRun = CountDownLatch(2)
        hk.every("sweep", hour) { secondRun.countDown() }
        hk.start() // the start-up run is the first count
        try {
            ticks.set(hour)
            assertTrue(secondRun.await(30, TimeUnit.SECONDS), "the background thread ran the job once it fell due")
        } finally {
            hk.close()
        }
    }
}
