package io.github.nanomuse.hands

import android.content.Context
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.shell.INanoMuseShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HandsStopTest {

    @Test
    fun `requestStop interrupts in-flight operations quickly`() {
        val context = mock(Context::class.java)
        val op = HandsOperator(context)

        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val thread = Thread {
            try {
                // Simulate setting the worker thread and doing work
                val workerField = HandsOperator::class.java.getDeclaredField("workerThread").apply { isAccessible = true }
                workerField.set(op, Thread.currentThread())
                started.countDown()
                Thread.sleep(10_000L)
            } catch (_: InterruptedException) {
                interrupted.set(true)
            }
        }
        thread.start()

        assertTrue(started.await(2, TimeUnit.SECONDS))
        op.requestStop("user requested stop")
        thread.join(2000L)

        assertFalse(thread.isAlive)
        assertTrue("Sleeping thread should be interrupted immediately", interrupted.get())
    }

    @Test
    fun `requestStop is idempotent`() {
        val context = mock(Context::class.java)
        val op = HandsOperator(context)

        op.requestStop("stop 1")
        val stopField = HandsOperator::class.java.getDeclaredField("stop").apply { isAccessible = true }
        val stopReasonField = HandsOperator::class.java.getDeclaredField("stopReason").apply { isAccessible = true }

        val stopVal = (stopField.get(op) as java.util.concurrent.atomic.AtomicBoolean).get()
        @Suppress("UNCHECKED_CAST")
        val reasonVal = (stopReasonField.get(op) as java.util.concurrent.atomic.AtomicReference<String?>).get()

        assertTrue(stopVal)
        assertEquals("stop 1", reasonVal)

        // Second stop should not overwrite reason
        op.requestStop("stop 2")
        @Suppress("UNCHECKED_CAST")
        val reasonVal2 = (stopReasonField.get(op) as java.util.concurrent.atomic.AtomicReference<String?>).get()
        assertEquals("stop 1", reasonVal2)
    }

    @Test
    fun `model timeout remains a retryable failure`() {
        val op = HandsOperator(mock(Context::class.java))

        val error = assertThrows(TimeoutCancellationException::class.java) {
            op.callModel(timeoutMs = 20) { awaitCancellation() }
        }

        assertFalse("A timeout must not be reported as a user Stop", op.isStopFailure(error))
        assertEquals("retry succeeded", op.callModel { "retry succeeded" })
    }

    @Test
    fun `stop cancels an active model coroutine`() {
        val op = HandsOperator(mock(Context::class.java))
        val started = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                op.callModel {
                    started.countDown()
                    try {
                        awaitCancellation()
                    } finally {
                        released.set(true)
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        thread.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            op.requestStop()
            thread.join(2000)

            assertFalse("The suspended model call must finish after Stop", thread.isAlive)
            assertTrue(released.get())
            assertTrue(failure.get() is CancellationException)
            assertTrue(op.isStopFailure(failure.get()!!))
        } finally {
            op.requestStop()
            thread.join(2000)
        }
    }

    @Test
    fun `stop before model registration prevents the provider call`() {
        val op = HandsOperator(mock(Context::class.java))
        var called = false
        op.requestStop()

        assertThrows(CancellationException::class.java) {
            op.callModel { called = true }
        }

        assertFalse(called)
    }

    @Test
    fun `double tap stops between the two taps`() {
        val op = HandsOperator(mock(Context::class.java))
        var taps = 0

        val completed = op.doubleTap(tap = { taps++; true }, between = { op.requestStop() })

        assertFalse(completed)
        assertEquals(1, taps)
    }

    @Test
    fun `virtual double tap does not inject a second tap after Stop`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        `when`(shell.injectTap(42, 10, 20)).thenReturn(true)
        val target = VirtualDisplayTarget(display, shell)
        val op = HandsOperator(mock(Context::class.java))

        assertFalse(op.doubleTap(tap = { target.tap(10, 20) }, between = { op.requestStop() }))

        verify(shell, times(1)).injectTap(42, 10, 20)
    }

    @Test
    fun `double tap respects a stop before its first tap`() {
        val op = HandsOperator(mock(Context::class.java))
        var taps = 0
        op.requestStop()

        assertFalse(op.doubleTap(tap = { taps++; true }))

        assertEquals(0, taps)
    }

    @Test
    fun `interrupt during double tap interval prevents its second tap`() {
        val op = HandsOperator(mock(Context::class.java))
        var taps = 0

        try {
            val completed = op.doubleTap(
                tap = { taps++; true },
                between = { Thread.currentThread().interrupt() },
            )

            assertFalse(completed)
            assertEquals(1, taps)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `double tap completes both taps when not stopped`() {
        val op = HandsOperator(mock(Context::class.java))
        var taps = 0

        assertTrue(op.doubleTap(tap = { taps++; true }))

        assertEquals(2, taps)
    }
}
