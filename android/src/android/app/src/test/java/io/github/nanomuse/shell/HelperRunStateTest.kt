package io.github.nanomuse.shell

import io.github.nanomuse.shell.setup.BuiltinShellFailure
import io.github.nanomuse.shell.setup.HelperRunState
import io.github.nanomuse.shell.setup.HelperRecoveryBudget
import io.github.nanomuse.shell.setup.PairingSession
import org.junit.Assert.*
import org.junit.Test

class HelperRunStateTest {
    @Test fun stopInvalidatesOutstandingStartAndNetworkRecovery() {
        val state = HelperRunState(true)
        val old = state.request()
        state.stop()
        assertFalse(state.desiredRunning)
        assertFalse(state.current(old))
        val retry = state.request()
        assertFalse(state.current(old))
        assertTrue(state.current(retry))
    }

    @Test fun authorizationFailuresDoNotRetryAndTransientRetriesAreBounded() {
        assertFalse(HelperRunState.retryable(BuiltinShellFailure.RootDenied))
        assertFalse(HelperRunState.retryable(BuiltinShellFailure.AuthorizationRevoked))
        assertFalse(HelperRunState.retryable(BuiltinShellFailure.KeyUnreadable))
        assertTrue(HelperRunState.retryable(BuiltinShellFailure.ConnectFailed))
        assertEquals(3, HelperRunState.RETRY_DELAYS_MS.size)
        assertEquals(HelperRunState.RETRY_DELAYS_MS.sorted(), HelperRunState.RETRY_DELAYS_MS)
    }

    @Test fun repeatedImmediateDeathsStopRestartingUntilStableOrExplicitRetry() {
        val budget = HelperRecoveryBudget()
        repeat(HelperRecoveryBudget.MAX_RESTARTS) { n ->
            budget.connected(n * 100L)
            assertTrue(budget.retryAfterDeath(n * 100L + 1))
        }
        budget.connected(1_000L)
        assertFalse(budget.retryAfterDeath(1_001L))
        budget.reset()
        assertTrue(budget.retryAfterDeath(1_002L))
        budget.connected(2_000L)
        assertTrue(budget.retryAfterDeath(2_000L + HelperRecoveryBudget.STABLE_MS))
    }

    @Test fun lateHandoffCannotAttachAfterStopOrSupersedeNewStart() {
        val state = HelperHandoffState()
        assertFalse(state.accepts(null))
        state.expect("first")
        state.cancel()
        assertFalse(state.accepts("first"))
        state.expect("second")
        state.cancel("first")
        assertFalse(state.accepts("first"))
        assertTrue(state.accepts("second"))
    }
    @Test fun closedPairingSessionRejectsLateRepliesAndOldScreenReadings() {
        val session = PairingSession()
        val old = session.begin()
        assertTrue(session.accepts(old))
        session.end()
        assertFalse(session.accepts(old))
        val next = session.begin()
        assertFalse(session.accepts(old))
        assertTrue(session.accepts(next))
    }

}
