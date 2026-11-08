package io.github.nanomuse.shell

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class HelperLifetimeTest {
    @Test fun clientDeathAndExplicitStopCleanUpOnce() {
        val cleaned = AtomicInteger()
        val exited = AtomicInteger()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val lifetime = HelperLifetime({ cleaned.incrementAndGet(); entered.countDown(); finish.await() }, { exited.incrementAndGet() })
        val death = Thread { lifetime.stop() }.apply { start() }
        entered.await()
        lifetime.stop()
        assertEquals(1, cleaned.get())
        assertEquals(0, exited.get())
        finish.countDown()
        death.join(2_000)
        assertFalse(death.isAlive)
        assertEquals(1, exited.get())
        lifetime.stop()
        assertEquals(1, exited.get())
    }

    @Test fun cleanupFailureStillExitsAndDoesNotRepeat() {
        val events = mutableListOf<String>()
        val lifetime = HelperLifetime({ events += "cleanup"; error("failed") }, { events += "exit" })
        assertTrue(runCatching { lifetime.stop() }.isFailure)
        lifetime.stop()
        assertEquals(listOf("cleanup", "exit"), events)
    }
}
