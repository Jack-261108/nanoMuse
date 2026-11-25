package io.github.nanomuse.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class UnifiedHelperTransportTest {

    private class FakeBinder(
        val id: IntArray,
        var alive: Boolean = true,
        var pingOk: Boolean = true,
        var failDestroy: Boolean = false,
        var onIdentity: (() -> Unit)? = null,
    ) {
        var destroyCount = 0
        var linked = 0
        var unlinked = 0
        private val deathCallbacks = mutableListOf<() -> Unit>()

        fun identity(): IntArray {
            onIdentity?.invoke()
            return id
        }

        fun destroy() {
            destroyCount++
            if (failDestroy) error("destroy ipc failed")
            alive = false
        }

        fun linkDeath(cb: () -> Unit): (() -> Unit)? {
            if (!alive) return null
            linked++
            deathCallbacks += cb
            return {
                unlinked++
                deathCallbacks -= cb
            }
        }

        fun simulateDeath() {
            alive = false
            deathCallbacks.toList().forEach { it() }
        }
    }

    private fun newStore(
        nowMs: () -> Long = { 1_000L },
        wirelessEnabled: () -> Boolean = { true },
        shizukuReady: () -> Boolean = { true },
        onStateChanged: (HelperSessionStore.Session<FakeBinder, FakeBinder>?, Boolean) -> Unit = { _, _ -> },
    ) = HelperSessionStore<FakeBinder, FakeBinder>(
        nowMs = nowMs,
        isShellAlive = { it.alive },
        pingShell = { it.pingOk },
        shellIdentity = { it.identity() },
        destroyShell = { it.destroy() },
        isDisplayAlive = { it.alive },
        pingDisplay = { it.pingOk },
        displayIdentity = { it.identity() },
        destroyDisplay = { it.destroy() },
        linkShellDeath = { s, cb -> s.linkDeath(cb) },
        linkDisplayDeath = { d, cb -> d.linkDeath(cb) },
        wirelessEnabled = wirelessEnabled,
        shizukuReady = shizukuReady,
        onStateChanged = onStateChanged,
    )

    @Test
    fun handoffStateEnforcesSingleUseExpiryAndGenerationInvalidation() {
        var now = 1_000L
        val state = HelperHandoffState { now }

        state.expect("ticket-1", ttlMs = 90_000L, origin = HelperOrigin.WIRELESS)
        assertNull(state.consume("wrong"))
        val claim = state.consume("ticket-1")
        assertNotNull(claim)
        assertEquals(HelperOrigin.WIRELESS, claim!!.origin)
        assertTrue(state.current(claim))
        // Nonce cannot be replayed after consumption.
        assertNull(state.consume("ticket-1"))

        // Explicit cancel invalidates an in-flight claim.
        state.cancel()
        assertFalse(state.current(claim))

        // Automated start expires at deadline.
        state.expect("ticket-2", ttlMs = 90_000L, origin = HelperOrigin.WIRELESS)
        now += 90_000L
        assertFalse(state.accepts("ticket-2"))
        assertNull(state.consume("ticket-2"))

        // Manual start without TTL remains valid across time until cancelled or superseded.
        state.expect("manual-1", ttlMs = null, origin = HelperOrigin.MANUAL)
        now += 600_000L
        assertTrue(state.accepts("manual-1"))
        state.cancel("other-nonce")
        assertTrue(state.accepts("manual-1"))
        val manualClaim = state.consume("manual-1")
        assertNotNull(manualClaim)
        state.expect("manual-2", ttlMs = null, origin = HelperOrigin.MANUAL)
        assertFalse(state.current(manualClaim!!))
    }

    @Test
    fun callerAuthEnforcesUidBoundariesForHandoffOriginAndShellIpc() {
        // Handoff provider caller checks
        assertTrue(HelperCallerAuth.acceptsHandoffCaller(2000, 123, 2000, 123, hasDisplayBinder = true))
        assertTrue(HelperCallerAuth.acceptsHandoffCaller(2000, 123, 2000, 123, hasDisplayBinder = false))
        assertTrue(HelperCallerAuth.acceptsHandoffCaller(0, 123, 0, 123, hasDisplayBinder = false))
        // Root must never deliver IHandsDisplay.
        assertFalse(HelperCallerAuth.acceptsHandoffCaller(0, 123, 0, 123, hasDisplayBinder = true))
        // Unprivileged app UID, spoofed PID/UID, or invalid PID are rejected.
        assertFalse(HelperCallerAuth.acceptsHandoffCaller(10250, 123, 10250, 123, hasDisplayBinder = false))
        assertFalse(HelperCallerAuth.acceptsHandoffCaller(2000, 123, 2000, 999, hasDisplayBinder = true))
        assertFalse(HelperCallerAuth.acceptsHandoffCaller(2000, 0, 2000, 0, hasDisplayBinder = false))

        // Origin checks
        assertTrue(HelperCallerAuth.acceptsOrigin(HelperOrigin.WIRELESS, 2000, wirelessReported = true, hasDisplayBinder = true))
        assertFalse(HelperCallerAuth.acceptsOrigin(HelperOrigin.WIRELESS, 2000, wirelessReported = false, hasDisplayBinder = true))
        assertFalse(HelperCallerAuth.acceptsOrigin(HelperOrigin.WIRELESS, 0, wirelessReported = true, hasDisplayBinder = false))
        assertTrue(HelperCallerAuth.acceptsOrigin(HelperOrigin.ROOT, 0, wirelessReported = false, hasDisplayBinder = false))
        assertFalse(HelperCallerAuth.acceptsOrigin(HelperOrigin.ROOT, 0, wirelessReported = false, hasDisplayBinder = true))
        assertTrue(HelperCallerAuth.acceptsOrigin(HelperOrigin.SHIZUKU, 2000, wirelessReported = false, hasDisplayBinder = true))
        assertTrue(HelperCallerAuth.acceptsOrigin(HelperOrigin.SHIZUKU, 0, wirelessReported = false, hasDisplayBinder = false))
        assertFalse(HelperCallerAuth.acceptsOrigin(HelperOrigin.SHIZUKU, 0, wirelessReported = false, hasDisplayBinder = true))
        assertFalse(HelperCallerAuth.acceptsOrigin(HelperOrigin.MANUAL, 2000, wirelessReported = true, hasDisplayBinder = true))

        // ShellService IPC caller UID checks
        val appUid = 10321
        assertTrue(HelperCallerAuth.acceptsShellTransaction(appUid, appUid, selfUid = 2000, isDestroy = false))
        assertFalse(HelperCallerAuth.acceptsShellTransaction(10999, appUid, selfUid = 2000, isDestroy = false))
        assertFalse(HelperCallerAuth.acceptsShellTransaction(2000, appUid, selfUid = 2000, isDestroy = false))
        assertFalse(HelperCallerAuth.acceptsShellTransaction(0, appUid, selfUid = 2000, isDestroy = false))
        // destroy allows owner app, self, shell, and root, never a third-party app.
        assertTrue(HelperCallerAuth.acceptsShellTransaction(appUid, appUid, selfUid = 2000, isDestroy = true))
        assertTrue(HelperCallerAuth.acceptsShellTransaction(2000, appUid, selfUid = 2000, isDestroy = true))
        assertTrue(HelperCallerAuth.acceptsShellTransaction(0, appUid, selfUid = 2000, isDestroy = true))
        assertFalse(HelperCallerAuth.acceptsShellTransaction(10999, appUid, selfUid = 2000, isDestroy = true))
    }

    @Test
    fun wirelessAttachDeathAndToggleKeepShellAliveWhileGatingDisplay() {
        var wirelessOn = true
        val events = mutableListOf<Pair<Boolean, Boolean>>()
        val store = newStore(
            wirelessEnabled = { wirelessOn },
            onStateChanged = { session, displayReady -> events += (session != null) to displayReady },
        )

        val shell = FakeBinder(intArrayOf(2000, 4242))
        val display = FakeBinder(intArrayOf(2000, 4242))
        store.prepareStart("nonce-w", ttlMs = 90_000L, origin = HelperOrigin.WIRELESS)
        assertTrue(store.attach(shell, display, pid = 4242, uid = 2000, nonce = "nonce-w", wirelessReported = true))
        assertSame(shell, store.currentShell())
        assertSame(display, store.currentDisplay())
        assertEquals(listOf(true to true), events)

        // Turning wireless debugging off keeps detached INanoMuseShell alive, but revokes IHandsDisplay.
        wirelessOn = false
        store.refreshReadiness()
        assertTrue(store.isShellReady())
        assertNull(store.currentDisplay())
        assertTrue(store.hasDisplayBinder())
        assertEquals(true to false, events.last())

        // Re-enabling wireless debugging restores IHandsDisplay without spawning a new process.
        wirelessOn = true
        store.refreshReadiness()
        assertSame(display, store.currentDisplay())
        assertEquals(true to true, events.last())

        // Helper death on the display binder atomically invalidates BOTH shell and display.
        display.simulateDeath()
        assertFalse(store.isShellReady())
        assertNull(store.currentShell())
        assertNull(store.currentDisplay())
        assertEquals(false to false, events.last())
        assertEquals(1, shell.unlinked)
        assertEquals(1, display.unlinked)
    }

    @Test
    fun manualUsbHelperAllowsDisplayWithoutWirelessAndRejectsDoubleAttach() {
        val store = newStore(wirelessEnabled = { false })
        val shell = FakeBinder(intArrayOf(2000, 5001))
        val display = FakeBinder(intArrayOf(2000, 5001))
        store.prepareStart("usb-1", ttlMs = null, origin = HelperOrigin.MANUAL)
        assertTrue(store.attach(shell, display, pid = 5001, uid = 2000, nonce = "usb-1", wirelessReported = false))
        assertSame(shell, store.currentShell())
        assertSame(display, store.currentDisplay())

        // Second helper cannot attach while the first is alive.
        store.prepareStart("usb-2", ttlMs = null, origin = HelperOrigin.MANUAL)
        val rivalShell = FakeBinder(intArrayOf(2000, 5002))
        assertFalse(store.attach(rivalShell, null, pid = 5002, uid = 2000, nonce = "usb-2", wirelessReported = false))
        assertSame(shell, store.currentShell())
        assertEquals(0, rivalShell.linked)
    }

    @Test
    fun shizukuRevocationInvalidatesDisplayWhileKeepingShellAndRootNeverExposesDisplay() {
        var shizukuOk = true
        val store = newStore(shizukuReady = { shizukuOk })
        val shell = FakeBinder(intArrayOf(2000, 6001))
        val display = FakeBinder(intArrayOf(2000, 6001))
        store.prepareStart("shiz-1", ttlMs = 90_000L, origin = HelperOrigin.SHIZUKU)
        assertTrue(store.attach(shell, display, pid = 6001, uid = 2000, nonce = "shiz-1", wirelessReported = false))
        assertSame(display, store.currentDisplay())

        shizukuOk = false
        assertNull(store.currentDisplay())
        assertTrue(store.isShellReady())

        // Release tears down both connections and calls destroy once.
        shell.failDestroy = true
        store.release()
        assertFalse(store.isShellReady())
        assertNull(store.currentDisplay())
        assertEquals(1, shell.destroyCount)
        // Fallback to display.destroy when shell.destroy fails.
        assertEquals(1, display.destroyCount)

        // Root helper cannot supply a display binder, only INanoMuseShell.
        val rootShell = FakeBinder(intArrayOf(0, 7001))
        val forbiddenDisplay = FakeBinder(intArrayOf(0, 7001))
        store.prepareStart("root-bad", ttlMs = 90_000L, origin = HelperOrigin.ROOT)
        assertFalse(store.attach(rootShell, forbiddenDisplay, pid = 7001, uid = 0, nonce = "root-bad", wirelessReported = false))
        assertFalse(store.isShellReady())

        store.prepareStart("root-ok", ttlMs = 90_000L, origin = HelperOrigin.ROOT)
        assertTrue(store.attach(rootShell, null, pid = 7001, uid = 0, nonce = "root-ok", wirelessReported = false))
        assertSame(rootShell, store.currentShell())
        assertNull(store.currentDisplay())
    }

    @Test
    fun inFlightReleaseOrSpoofedIdentityRejectsAttachWithoutLeak() {
        lateinit var store: HelperSessionStore<FakeBinder, FakeBinder>
        store = newStore()

        // Spoofed display PID must reject the entire handoff.
        val goodShell = FakeBinder(intArrayOf(2000, 8001))
        val spoofedDisplay = FakeBinder(intArrayOf(2000, 9999))
        store.prepareStart("spoof", ttlMs = 90_000L, origin = HelperOrigin.WIRELESS)
        assertFalse(store.attach(goodShell, spoofedDisplay, pid = 8001, uid = 2000, nonce = "spoof", wirelessReported = true))
        assertFalse(store.isShellReady())
        assertNull(store.currentDisplay())

        // Concurrent release during identity verification invalidates the in-flight claim.
        val racingShell = FakeBinder(intArrayOf(2000, 8002), onIdentity = { store.release() })
        val racingDisplay = FakeBinder(intArrayOf(2000, 8002))
        store.prepareStart("race", ttlMs = 90_000L, origin = HelperOrigin.WIRELESS)
        assertFalse(store.attach(racingShell, racingDisplay, pid = 8002, uid = 2000, nonce = "race", wirelessReported = true))
        assertFalse(store.isShellReady())
        assertNull(store.currentDisplay())
        assertEquals(racingShell.linked, racingShell.unlinked)
        assertEquals(racingDisplay.linked, racingDisplay.unlinked)
    }

    @Test
    fun sharedHelperLifetimeRunsBothDisplayAndShellCleanupsExactlyOnce() {
        val displayCleaned = AtomicInteger()
        val shellCleaned = AtomicInteger()
        val exited = AtomicInteger()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)

        val lifetime = HelperLifetime(
            cleanup = {
                displayCleaned.incrementAndGet()
                entered.countDown()
                proceed.await()
                shellCleaned.incrementAndGet()
            },
            exit = { exited.incrementAndGet() },
        )

        val fromDisplayDestroy = Thread { lifetime.stop() }.apply { start() }
        entered.await()
        // Concurrent shell.destroy() and client death while display destroy is cleaning up.
        lifetime.stop()
        lifetime.stop()
        proceed.countDown()
        fromDisplayDestroy.join(2_000)

        assertEquals(1, displayCleaned.get())
        assertEquals(1, shellCleaned.get())
        assertEquals(1, exited.get())
    }
}
