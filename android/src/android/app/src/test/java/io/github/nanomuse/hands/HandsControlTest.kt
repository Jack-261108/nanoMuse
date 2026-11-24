package io.github.nanomuse.hands

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HandsControlTest {

    @Rule
    @JvmField
    val testTimeout: Timeout = Timeout.seconds(15)

    @Test
    fun `agent actions stop once a person asks for control`() {
        val control = HandsControl()

        assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 2_000L))
        assertEquals(HandsControl.State.Manual, control.current)
        assertNull(control.automatic { 1 })
        assertEquals(1, control.userInput { 1 })
    }

    @Test
    fun `hand back wakes the agent and returns the new version atomically`() {
        val control = HandsControl()
        val before = control.version
        val entered = CountDownLatch(1)
        val observedVersion = AtomicReference<Long?>()

        assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 2_000L))
        val agent = Thread {
            entered.countDown()
            observedVersion.set(control.awaitAutomaticVersion())
        }
        agent.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            awaitBlocked(agent)
            assertNull(observedVersion.get())

            assertTrue(control.handBack())
            agent.join(1_000)
            assertFalse(agent.isAlive)
            assertEquals(HandsControl.State.Automatic, control.current)
            val version = observedVersion.get()
            assertNotNull(version)
            assertEquals(control.version, version)
            assertTrue(version!! >= before + 2)
        } finally {
            control.close()
            agent.join(1_000)
        }
    }

    @Test
    fun `takeover waits for the action already in flight`() {
        val control = HandsControl()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actionDone = AtomicBoolean(false)
        val result = AtomicReference<HandsControl.BeginResult?>()

        val agent = Thread {
            control.automatic {
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                actionDone.set(true)
            }
        }
        val person = Thread {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            result.set(control.begin(timeoutMs = 2_000L))
        }
        agent.start()
        person.start()
        try {
            awaitState(control, HandsControl.State.Requesting)
            assertNull("takeover must not cut an action in half", result.get())

            release.countDown()
            agent.join(1_000)
            person.join(1_000)
            assertTrue(actionDone.get())
            assertEquals(HandsControl.BeginResult.Acquired, result.get())
            assertEquals(HandsControl.State.Manual, control.current)
        } finally {
            release.countDown()
            control.close()
            agent.join(1_000)
            person.join(1_000)
        }
    }

    @Test
    fun `concurrent begin does not roll back an active request`() {
        val control = HandsControl()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstResult = AtomicReference<HandsControl.BeginResult?>()

        val agent = Thread {
            control.automatic {
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        }
        val firstPerson = Thread {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            firstResult.set(control.begin(timeoutMs = 2_000L))
        }
        agent.start()
        firstPerson.start()
        try {
            awaitState(control, HandsControl.State.Requesting)

            // Second concurrent begin must fail with BusyOrClosed and leave the first request intact.
            assertEquals(HandsControl.BeginResult.BusyOrClosed, control.begin(timeoutMs = 2_000L))
            assertEquals(HandsControl.State.Requesting, control.current)

            release.countDown()
            agent.join(1_000)
            firstPerson.join(1_000)
            assertEquals(HandsControl.BeginResult.Acquired, firstResult.get())
            assertEquals(HandsControl.State.Manual, control.current)
        } finally {
            release.countDown()
            control.close()
            agent.join(1_000)
            firstPerson.join(1_000)
        }
    }

    @Test
    fun `begin timeout rolls back to Automatic and reports TimedOut`() {
        val control = HandsControl()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val agent = Thread {
            control.automatic {
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        }
        agent.start()
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))

            assertEquals(HandsControl.BeginResult.TimedOut, control.begin(timeoutMs = 50L))
            assertEquals(HandsControl.State.Automatic, control.current)

            release.countDown()
            agent.join(1_000)
            // A later takeover succeeds cleanly after the action completes.
            assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 2_000L))
            assertEquals(HandsControl.State.Manual, control.current)
        } finally {
            release.countDown()
            control.close()
            agent.join(1_000)
        }
    }

    @Test
    fun `manual input runs in order and is rejected after hand back`() {
        val control = HandsControl()
        val calls = AtomicInteger()
        assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 2_000L))

        control.userInput { calls.incrementAndGet() }
        control.userInput { calls.incrementAndGet() }
        assertTrue(control.handBack())

        assertNull(control.userInput { calls.incrementAndGet() })
        assertEquals(2, calls.get())
    }

    @Test
    fun `userInput does not hold the lock while running and handBack waits for it`() {
        val control = HandsControl()
        assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 2_000L))
        val inputStarted = CountDownLatch(1)
        val releaseInput = CountDownLatch(1)
        val inputDone = AtomicBoolean(false)
        val handBackDone = AtomicBoolean(false)

        val userThread = Thread {
            control.userInput {
                inputStarted.countDown()
                assertTrue(releaseInput.await(2, TimeUnit.SECONDS))
                inputDone.set(true)
            }
        }
        val handBackThread = Thread {
            assertTrue(control.handBack())
            handBackDone.set(true)
        }
        userThread.start()
        try {
            assertTrue(inputStarted.await(1, TimeUnit.SECONDS))

            // Reading current and version must not block on the in-flight userInput.
            assertEquals(HandsControl.State.Manual, control.current)
            val v = control.version

            handBackThread.start()
            awaitBlocked(handBackThread)
            assertFalse("handBack must wait for the in-flight user input", handBackDone.get())
            // New userInput calls arriving during handBack must be rejected immediately.
            assertNull(control.userInput { 42 })

            releaseInput.countDown()
            userThread.join(1_000)
            handBackThread.join(1_000)
            assertTrue(inputDone.get())
            assertTrue(handBackDone.get())
            assertEquals(HandsControl.State.Automatic, control.current)
            assertTrue(control.version > v)
            assertTrue(control.lastUserInputAtNanos > 0L)
        } finally {
            releaseInput.countDown()
            control.close()
            userThread.join(1_000)
            handBackThread.join(1_000)
        }
    }

    @Test
    fun `close wakes a waiting awaitAutomatic`() {
        val control = HandsControl()
        assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 2_000L))
        val result = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val thread = Thread {
            entered.countDown()
            result.set(control.awaitAutomatic())
        }
        thread.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            awaitBlocked(thread)

            control.close()
            thread.join(1_000)
            assertFalse(thread.isAlive)
            assertFalse(result.get())
            assertEquals(HandsControl.State.Closed, control.current)
        } finally {
            control.close()
            thread.join(1_000)
        }
    }

    @Test
    fun `close wakes a waiting begin and returns BusyOrClosed`() {
        val control = HandsControl()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val beginResult = AtomicReference<HandsControl.BeginResult?>()

        val agent = Thread {
            control.automatic {
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        }
        val person = Thread {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            beginResult.set(control.begin(timeoutMs = 2_000L))
        }
        agent.start()
        person.start()
        try {
            awaitState(control, HandsControl.State.Requesting)

            control.close()
            person.join(1_000)
            assertFalse(person.isAlive)
            assertEquals(HandsControl.BeginResult.BusyOrClosed, beginResult.get())
            assertEquals(HandsControl.State.Closed, control.current)
        } finally {
            release.countDown()
            control.close()
            agent.join(1_000)
            person.join(1_000)
        }
    }

    @Test
    fun `awaitAutomatic stops promptly when stopped callback becomes true`() {
        val control = HandsControl()
        assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 2_000L))
        val stopped = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val versionResult = AtomicReference<Long?>(99L)

        val agent = Thread {
            entered.countDown()
            versionResult.set(control.awaitAutomaticVersion { stopped.get() })
        }
        agent.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            awaitBlocked(agent)

            stopped.set(true)
            agent.join(1_000)
            assertFalse(agent.isAlive)
            assertNull(versionResult.get())
        } finally {
            stopped.set(true)
            control.close()
            agent.join(1_000)
        }
    }

    @Test
    fun `hand back cancels a takeover that is waiting for the current action`() {
        val control = HandsControl()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val beginResult = AtomicReference<HandsControl.BeginResult?>()

        val agent = Thread {
            control.automatic {
                started.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        }
        val person = Thread {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            beginResult.set(control.begin(timeoutMs = 2_000L))
        }
        agent.start()
        person.start()
        try {
            awaitState(control, HandsControl.State.Requesting)

            assertTrue(control.handBack())
            person.join(1_000)
            assertEquals(HandsControl.BeginResult.Cancelled, beginResult.get())
            assertEquals(HandsControl.State.Automatic, control.current)
        } finally {
            release.countDown()
            control.close()
            agent.join(1_000)
            person.join(1_000)
        }
    }

    @Test
    fun `automatic restores action count when block throws`() {
        val control = HandsControl()

        assertThrows(IllegalStateException::class.java) {
            control.automatic<Unit> { throw IllegalStateException("boom") }
        }

        assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 200L))
        assertEquals(HandsControl.State.Manual, control.current)
    }

    @Test
    fun `userInput restores active count when block throws`() {
        val control = HandsControl()
        assertEquals(HandsControl.BeginResult.Acquired, control.begin(timeoutMs = 2_000L))

        assertThrows(IllegalStateException::class.java) {
            control.userInput<Unit> { throw IllegalStateException("boom") }
        }

        assertTrue(control.handBack())
        assertEquals(HandsControl.State.Automatic, control.current)
    }

    @Test
    fun `nested automatic on the same thread completes during Requesting but stops on Closed`() {
        val control = HandsControl()
        val firstDone = CountDownLatch(1)
        val continueSecond = CountDownLatch(1)
        val innerResult = AtomicReference<String?>()
        val beginResult = AtomicReference<HandsControl.BeginResult?>()

        val agent = Thread {
            control.automatic {
                firstDone.countDown()
                assertTrue(continueSecond.await(2, TimeUnit.SECONDS))
                innerResult.set(control.automatic { "completed" })
            }
        }
        val person = Thread {
            assertTrue(firstDone.await(1, TimeUnit.SECONDS))
            beginResult.set(control.begin(timeoutMs = 2_000L))
        }
        agent.start()
        person.start()
        try {
            awaitState(control, HandsControl.State.Requesting)

            continueSecond.countDown()
            agent.join(1_000)
            person.join(1_000)
            assertEquals("completed", innerResult.get())
            assertEquals(HandsControl.BeginResult.Acquired, beginResult.get())
        } finally {
            continueSecond.countDown()
            control.close()
            agent.join(1_000)
            person.join(1_000)
        }

        // Once closed, nested automatic must also return null.
        val closedControl = HandsControl()
        var closedInner: String? = "unexpected"
        closedControl.automatic {
            closedControl.close()
            closedInner = closedControl.automatic { "should-not-run" }
        }
        assertNull(closedInner)
    }

    @Test
    fun `state StateFlow publishes every lifecycle transition`() = runBlocking {
        val control = HandsControl()
        val seen = CopyOnWriteArrayList<HandsControl.State>()
        val sawInitial = CountDownLatch(1)
        val sawRequesting = CountDownLatch(1)
        val sawManual = CountDownLatch(1)
        val sawReturned = CountDownLatch(1)
        val sawClosed = CountDownLatch(1)
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)

        val collector = launch(Dispatchers.Default) {
            control.state.collect { st ->
                seen += st
                when (st) {
                    HandsControl.State.Automatic -> {
                        if (seen.size == 1) sawInitial.countDown()
                        else if (seen.contains(HandsControl.State.Manual)) sawReturned.countDown()
                    }
                    HandsControl.State.Requesting -> sawRequesting.countDown()
                    HandsControl.State.Manual -> sawManual.countDown()
                    HandsControl.State.Closed -> sawClosed.countDown()
                }
            }
        }
        val agent = Thread {
            control.automatic {
                actionStarted.countDown()
                assertTrue(releaseAction.await(2, TimeUnit.SECONDS))
            }
        }
        val person = Thread { control.begin(timeoutMs = 2_000L) }
        try {
            assertTrue(sawInitial.await(1, TimeUnit.SECONDS))
            agent.start()
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS))

            person.start()
            assertTrue(sawRequesting.await(1, TimeUnit.SECONDS))

            releaseAction.countDown()
            agent.join(1_000)
            person.join(1_000)
            assertTrue(sawManual.await(1, TimeUnit.SECONDS))

            assertTrue(control.handBack())
            assertTrue(sawReturned.await(1, TimeUnit.SECONDS))

            control.close()
            assertTrue(sawClosed.await(1, TimeUnit.SECONDS))

            assertEquals(
                listOf(
                    HandsControl.State.Automatic,
                    HandsControl.State.Requesting,
                    HandsControl.State.Manual,
                    HandsControl.State.Automatic,
                    HandsControl.State.Closed,
                ),
                seen,
            )
        } finally {
            releaseAction.countDown()
            control.close()
            collector.cancel()
            agent.join(1_000)
            person.join(1_000)
        }
    }

    private fun awaitState(control: HandsControl, expected: HandsControl.State) {
        val deadline = System.currentTimeMillis() + 2_000
        while (control.current != expected && System.currentTimeMillis() < deadline) {
            Thread.yield()
        }
        assertEquals(expected, control.current)
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.currentTimeMillis() + 2_000
        while (thread.state != Thread.State.WAITING &&
            thread.state != Thread.State.TIMED_WAITING &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.yield()
        }
        assertTrue(
            "expected thread to be waiting, was ${thread.state}",
            thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING,
        )
    }
}
