package io.github.nanomuse.shell

import java.security.MessageDigest

/** Caller authorization rules shared by the handoff provider and ShellService IPC. */
internal object HelperCallerAuth {
    fun acceptsHandoffCaller(
        callingUid: Int,
        callingPid: Int,
        reportedUid: Int,
        reportedPid: Int,
        hasDisplayBinder: Boolean,
    ): Boolean {
        if (callingUid != HelperHandoff.SHELL_UID && callingUid != HelperHandoff.ROOT_UID) return false
        if (callingPid <= 0 || reportedUid != callingUid || reportedPid != callingPid) return false
        if (hasDisplayBinder && callingUid != HelperHandoff.SHELL_UID) return false
        return true
    }

    fun acceptsOrigin(
        origin: HelperOrigin,
        uid: Int,
        wirelessReported: Boolean,
        hasDisplayBinder: Boolean,
    ): Boolean = when (origin) {
        HelperOrigin.WIRELESS -> uid == HelperHandoff.SHELL_UID && wirelessReported
        HelperOrigin.SHIZUKU -> !wirelessReported &&
            (uid == HelperHandoff.SHELL_UID || (uid == HelperHandoff.ROOT_UID && !hasDisplayBinder))
        HelperOrigin.ROOT -> uid == HelperHandoff.ROOT_UID && !wirelessReported && !hasDisplayBinder
        HelperOrigin.MANUAL -> !wirelessReported &&
            (uid == HelperHandoff.SHELL_UID || (uid == HelperHandoff.ROOT_UID && !hasDisplayBinder))
    }

    fun acceptsShellTransaction(
        callingUid: Int,
        allowedUid: Int,
        selfUid: Int,
        isDestroy: Boolean,
    ): Boolean {
        if (callingUid == allowedUid) return true
        if (!isDestroy) return false
        return callingUid == selfUid ||
            callingUid == HelperHandoff.ROOT_UID ||
            callingUid == HelperHandoff.SHELL_UID
    }
}

/** Reject late handoffs after stop, expiry, or after another start superseded the old one. */
internal class HelperHandoffState(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    internal data class Claim(val generation: Long, val origin: HelperOrigin)

    private var generation = 0L
    private var expected: String? = null
    private var expectedOrigin: HelperOrigin = HelperOrigin.MANUAL
    private var expiresAt: Long? = null

    fun expect(nonce: String, ttlMs: Long? = null, origin: HelperOrigin = HelperOrigin.MANUAL) {
        generation++
        expected = nonce
        expectedOrigin = origin
        expiresAt = ttlMs?.let { nowMs() + it }
    }

    fun accepts(nonce: String?): Boolean {
        val target = expected ?: return false
        if (nonce == null) return false
        val deadline = expiresAt
        if (deadline != null && nowMs() >= deadline) return false
        return constantTimeEquals(target, nonce)
    }

    /** Consumes the single-use ticket and returns its claim, or null if invalid or expired. */
    fun consume(nonce: String?): Claim? {
        if (!accepts(nonce)) return null
        val claim = Claim(generation, expectedOrigin)
        expected = null
        expiresAt = null
        return claim
    }

    /** True if no stop or newer start has invalidated [claim] while verification was in flight. */
    fun current(claim: Claim): Boolean = generation == claim.generation

    fun cancel(nonce: String? = null) {
        if (nonce == null) {
            generation++
            expected = null
            expiresAt = null
        } else if (expected != null && constantTimeEquals(expected!!, nonce)) {
            generation++
            expected = null
            expiresAt = null
        }
    }

    companion object {
        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
