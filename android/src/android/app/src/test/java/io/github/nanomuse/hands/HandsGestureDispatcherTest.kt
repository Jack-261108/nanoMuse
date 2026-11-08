package io.github.nanomuse.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HandsGestureDispatcherTest {
    @Test
    fun `stopped work never enters the input queue`() {
        val posted = AtomicBoolean(false)
        val accepted = HandsGestureDispatcher.dispatchQueued(
            isStopped = { true },
            post = { posted.set(true); true },
            remove = {},
            submit = { error("Stopped work must not submit input") },
        )
        assertFalse(accepted)
        assertFalse(posted.get())
    }

    @Test
    fun `an interrupted worker never enters the input queue`() {
        val posted = AtomicBoolean(false)
        val result = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                Thread.currentThread().interrupt()
                result.set(HandsGestureDispatcher.dispatchQueued(
                    isStopped = { false },
                    post = { posted.set(true); true },
                    remove = {},
                    submit = { error("Interrupted work must not submit input") },
                ))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        worker.join(2_000L)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertFalse(result.get())
        assertFalse(posted.get())
    }

    @Test
    fun `stop between posting and dispatch prevents the gesture`() {
        val queue = PendingGesture()
        queue.start()
        queue.awaitPosted()
        queue.stopped.set(true)
        queue.request.get().run()
        queue.join()
        assertFalse(queue.result.get())
        assertEquals(0, queue.submissions.get())
        assertTrue(queue.removed.get())
    }

    @Test
    fun `interrupt removes queued input and a late runnable remains inert`() {
        val queue = PendingGesture()
        queue.start()
        queue.awaitPosted()
        queue.worker.interrupt()
        queue.join()
        assertTrue(queue.failure.get() is InterruptedException)
        assertTrue(queue.removed.get())
        // The main thread may already have dequeued the runnable when removal happens.
        queue.request.get().run()
        assertEquals(0, queue.submissions.get())
    }

    @Test
    fun `timeout removes queued input and a late runnable remains inert`() {
        val queue = PendingGesture(timeoutMs = 30L)
        queue.start()
        queue.awaitPosted()
        queue.join()
        assertFalse(queue.result.get())
        assertTrue(queue.removed.get())
        queue.request.get().run()
        assertEquals(0, queue.submissions.get())
    }

    @Test
    fun `a rejected gesture returns without waiting for a callback`() {
        val queue = PendingGesture(accept = false)
        queue.start()
        queue.awaitPosted()
        queue.request.get().run()
        queue.join()
        assertFalse(queue.result.get())
        assertEquals(1, queue.submissions.get())
        assertTrue(queue.removed.get())
    }

    @Test
    fun `completion reports success and cancellation reports failure`() {
        for (completed in listOf(true, false)) {
            val queue = PendingGesture()
            queue.start()
            queue.awaitPosted()
            queue.request.get().run()
            queue.callback.get().invoke(completed)
            queue.join()
            assertEquals(completed, queue.result.get())
            assertEquals(1, queue.submissions.get())
        }
    }

    @Test
    fun `stop before completion does not report a successful action`() {
        val queue = PendingGesture()
        queue.start()
        queue.awaitPosted()
        queue.request.get().run()
        queue.stopped.set(true)
        queue.callback.get().invoke(true)
        queue.join()
        assertFalse(queue.result.get())
        assertEquals(1, queue.submissions.get())
    }

    @Test
    fun `interrupt after submission returns while the old callback remains harmless`() {
        val queue = PendingGesture()
        queue.start()
        queue.awaitPosted()
        queue.request.get().run()
        queue.worker.interrupt()
        queue.join()
        assertTrue(queue.failure.get() is InterruptedException)
        queue.callback.get().invoke(true)
        assertEquals(1, queue.submissions.get())
        assertTrue(queue.removed.get())
    }

    @Test
    fun `a post failure does not wait or submit input`() {
        assertFalse(HandsGestureDispatcher.dispatchQueued(
            isStopped = { false },
            post = { false },
            remove = {},
            submit = { error("Rejected post must not submit input") },
        ))
    }

    @Test
    fun `a layout change after preparation rejects input on the dispatch queue`() {
        var placementStillSafe = true
        var submitted = false
        assertFalse(HandsGestureDispatcher.dispatchQueued(
            isStopped = { false },
            post = {
                placementStillSafe = false
                it.run()
                true
            },
            remove = {},
            canDispatch = { placementStillSafe },
            submit = { submitted = true; true },
        ))
        assertFalse(submitted)
    }

    @Test
    fun `a dispatch error reaches the worker`() {
        val failure = IllegalStateException("Gesture dispatch failed")
        var actual: Throwable? = null
        try {
            HandsGestureDispatcher.dispatchQueued(
                isStopped = { false },
                post = { it.run(); true },
                remove = {},
                submit = { throw failure },
            )
        } catch (error: Exception) {
            actual = error
        }
        assertSame(failure, actual)
    }

    private class PendingGesture(
        private val accept: Boolean = true,
        private val timeoutMs: Long = 5_000L,
    ) {
        val stopped = AtomicBoolean(false)
        val request = AtomicReference<Runnable>()
        val callback = AtomicReference<(Boolean) -> Unit>()
        val submissions = AtomicInteger(0)
        val removed = AtomicBoolean(false)
        val result = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        private val posted = CountDownLatch(1)
        val worker = Thread {
            try {
                result.set(HandsGestureDispatcher.dispatchQueued(
                    isStopped = { stopped.get() },
                    post = {
                        request.set(it)
                        posted.countDown()
                        true
                    },
                    remove = { removed.set(true) },
                    submit = {
                        submissions.incrementAndGet()
                        callback.set(it)
                        accept
                    },
                    timeoutMs = timeoutMs,
                ))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true }

        fun start() = worker.start()

        fun awaitPosted() {
            assertTrue("The worker should post input", posted.await(2, TimeUnit.SECONDS))
        }

        fun join() {
            worker.join(2_000L)
            if (worker.isAlive) {
                worker.interrupt()
                worker.join(2_000L)
                throw AssertionError("The worker did not stop waiting for input")
            }
            failure.get()?.let { if (it !is InterruptedException) throw AssertionError(it) }
        }
    }
}
