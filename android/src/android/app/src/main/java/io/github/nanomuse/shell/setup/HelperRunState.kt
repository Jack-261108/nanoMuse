package io.github.nanomuse.shell.setup

/** Invalidates outstanding starts before cleanup begins, including late network callbacks. */
internal class HelperRunState(initiallyWanted: Boolean) {
    @Volatile var desiredRunning: Boolean = initiallyWanted
        private set
    private var generation = 0L

    @Synchronized fun request(): Long {
        desiredRunning = true
        return ++generation
    }

    @Synchronized fun stop() {
        desiredRunning = false
        generation++
    }

    @Synchronized fun current(token: Long): Boolean = desiredRunning && token == generation

    companion object {
        val RETRY_DELAYS_MS = listOf(1_000L, 3_000L, 10_000L)
        fun retryable(reason: BuiltinShellFailure?): Boolean = reason in setOf(
            BuiltinShellFailure.ConnectFailed,
            BuiltinShellFailure.HelperStartFailed,
        )
    }
}

/** A helper that crashes immediately must not cause an endless spawn loop. */
internal class HelperRecoveryBudget {
    private var connectedAt = 0L
    private var restarts = 0

    @Synchronized fun connected(now: Long) { connectedAt = now }
    @Synchronized fun reset() { restarts = 0 }
    @Synchronized fun retryAfterDeath(now: Long): Boolean {
        if (now - connectedAt >= STABLE_MS) restarts = 0
        if (restarts >= MAX_RESTARTS) return false
        restarts++
        return true
    }

    companion object {
        const val MAX_RESTARTS = 3
        const val STABLE_MS = 60_000L
    }
}
