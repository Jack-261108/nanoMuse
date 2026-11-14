package io.github.nanomuse.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFreshnessTest {

    /** A millisecond budget clock the test advances by hand. */
    private class Clock(var nowMs: Long = 1_000L) {
        fun read(): Long = nowMs
        fun sleepUntil(ms: Long): Boolean {
            nowMs += ms
            return true
        }
    }

    /** The launch instant on the producer's nanosecond clock; frames compare against this. */
    private val since = 5_000_000_000_000L

    @Test
    fun `a frame newer than the launch returns at once`() {
        val clock = Clock()
        val fresh = FrameFreshness.awaitNewFrame(
            nowMs = clock::read,
            sleep = clock::sleepUntil,
            frameAt = { since + 1_000_000L },
            since = since,
        )
        assertTrue(fresh)
        assertTrue("a frame already in hand must not wait", clock.nowMs == 1_000L)
    }

    @Test
    fun `the budget runs in milliseconds on its own clock`() {
        val clock = Clock()
        val fresh = FrameFreshness.awaitNewFrame(
            nowMs = clock::read,
            sleep = clock::sleepUntil,
            frameAt = { null },
            since = since,
            timeoutMs = 300L,
        )
        assertFalse(fresh)
        assertTrue(
            "300 ms stays 300 ms on the budget clock; nanoseconds never leak into it",
            clock.nowMs >= 1_300L && clock.nowMs <= 1_400L,
        )
    }

    @Test
    fun `an older frame is not accepted`() {
        val clock = Clock()
        val fresh = FrameFreshness.awaitNewFrame(
            nowMs = clock::read,
            sleep = clock::sleepUntil,
            frameAt = { since - 1L },
            since = since,
            timeoutMs = 300L,
        )
        assertFalse(fresh)
    }

    @Test
    fun `a frame that arrives during the wait is accepted`() {
        val clock = Clock()
        var at: Long? = since - 1L
        val fresh = FrameFreshness.awaitNewFrame(
            nowMs = clock::read,
            sleep = { ms -> clock.sleepUntil(ms).also { if (clock.nowMs >= 1_200L) at = since + 1L } },
            frameAt = { at },
            since = since,
            timeoutMs = 600L,
        )
        assertTrue(fresh)
    }

    @Test
    fun `a stopped wait gives up before the timeout`() {
        val clock = Clock()
        val fresh = FrameFreshness.awaitNewFrame(
            nowMs = clock::read,
            sleep = { false },
            frameAt = { null },
            since = since,
            timeoutMs = 10_000L,
        )
        assertFalse(fresh)
        assertTrue("a stop must not burn the whole budget", clock.nowMs < 5_000L)
    }
}
