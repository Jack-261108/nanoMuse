package io.github.nanomuse.shell

/**
 * Pure JVM-testable session state machine for one privileged helper exposing both
 * INanoMuseShell ([S]) and optionally IHandsDisplay ([D]).
 *
 * Guarantees single-use handoff verification outside the state lock, generation checks against
 * concurrent stop/restart, and atomic invalidation of both connections on death or release.
 */
internal class HelperSessionStore<S : Any, D : Any>(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val isShellAlive: (S) -> Boolean,
    private val pingShell: (S) -> Boolean,
    private val shellIdentity: (S) -> IntArray?,
    private val destroyShell: (S) -> Unit,
    private val isDisplayAlive: (D) -> Boolean,
    private val pingDisplay: (D) -> Boolean,
    private val displayIdentity: (D) -> IntArray?,
    private val destroyDisplay: (D) -> Unit,
    private val linkShellDeath: (S, () -> Unit) -> (() -> Unit)?,
    private val linkDisplayDeath: (D, () -> Unit) -> (() -> Unit)?,
    private val sameBinder: (S, D) -> Boolean = { _, _ -> false },
    private val wirelessEnabled: () -> Boolean = { true },
    private val shizukuReady: () -> Boolean = { true },
    private val onStateChanged: (Session<S, D>?, Boolean) -> Unit = { _, _ -> },
) {
    internal data class Session<S : Any, D : Any>(
        val shell: S,
        val display: D?,
        val pid: Int,
        val uid: Int,
        val origin: HelperOrigin,
    )

    private val lock = Any()
    private val handoff = HelperHandoffState(nowMs)
    private var active: Session<S, D>? = null
    private var unlinkShell: (() -> Unit)? = null
    private var unlinkDisplay: (() -> Unit)? = null

    fun currentSession(): Session<S, D>? = synchronized(lock) { active }

    fun currentShell(): S? = synchronized(lock) { active?.shell?.takeIf(isShellAlive) }

    fun isShellReady(): Boolean = currentShell() != null

    fun hasDisplayBinder(): Boolean = synchronized(lock) {
        val current = active ?: return false
        current.display != null && isShellAlive(current.shell) && isDisplayAlive(current.display)
    }

    fun currentDisplay(): D? {
        val snapshot = synchronized(lock) { active } ?: return null
        val display = snapshot.display ?: return null
        if (!isShellAlive(snapshot.shell) || !isDisplayAlive(display)) return null
        if (snapshot.uid != HelperHandoff.SHELL_UID) return null
        if (!isDisplayAuthorized(snapshot.origin)) return null
        return display
    }

    private fun isDisplayAuthorized(origin: HelperOrigin): Boolean = when (origin) {
        HelperOrigin.WIRELESS -> wirelessEnabled()
        HelperOrigin.SHIZUKU -> shizukuReady()
        HelperOrigin.MANUAL -> true
        HelperOrigin.ROOT -> false
    }

    fun prepareStart(nonce: String, ttlMs: Long? = null, origin: HelperOrigin = HelperOrigin.MANUAL) {
        synchronized(lock) { handoff.expect(nonce, ttlMs, origin) }
    }

    fun cancelStart(nonce: String? = null) {
        synchronized(lock) { handoff.cancel(nonce) }
    }

    fun attach(
        shell: S,
        display: D?,
        pid: Int,
        uid: Int,
        nonce: String?,
        wirelessReported: Boolean,
        verifyIdentity: Boolean = true,
    ): Boolean {
        val claim = synchronized(lock) {
            if (active?.shell?.let(isShellAlive) == true) return false
            val consumed = handoff.consume(nonce) ?: return false
            if (verifyIdentity && !HelperCallerAuth.acceptsOrigin(consumed.origin, uid, wirelessReported, display != null)) {
                return false
            }
            consumed
        }

        if (!isShellAlive(shell) || !pingShell(shell)) return false
        if (verifyIdentity) {
            val expected = intArrayOf(uid, pid)
            if (shellIdentity(shell)?.contentEquals(expected) != true) return false
            if (display != null) {
                if (uid != HelperHandoff.SHELL_UID) return false
                if (!isDisplayAlive(display) || !pingDisplay(display)) return false
                if (displayIdentity(display)?.contentEquals(expected) != true) return false
            }
        } else if (display != null && (!isDisplayAlive(display) || !pingDisplay(display))) {
            return false
        }

        val shellUnlink = linkShellDeath(shell) { onDeath(shell, display) } ?: return false
        val displayUnlink = if (display != null && !sameBinder(shell, display)) {
            val unlink = linkDisplayDeath(display) { onDeath(shell, display) }
            if (unlink == null) {
                runCatching(shellUnlink)
                return false
            }
            unlink
        } else null

        val committed: Session<S, D>? = synchronized(lock) {
            val aliveNow = isShellAlive(shell) && (display == null || isDisplayAlive(display))
            if (!handoff.current(claim) || active?.shell?.let(isShellAlive) == true || !aliveNow) {
                null
            } else {
                detachLocked()
                unlinkShell = shellUnlink
                unlinkDisplay = displayUnlink
                Session(shell, display, pid, uid, claim.origin).also { active = it }
            }
        }

        if (committed == null) {
            runCatching(shellUnlink)
            displayUnlink?.let { runCatching(it) }
            return false
        }
        notifyState()
        return true
    }

    fun detachOnly() {
        val changed = synchronized(lock) {
            val had = active != null
            detachLocked()
            had
        }
        if (changed) notifyState()
    }

    fun release() {
        val old = synchronized(lock) {
            handoff.cancel()
            active.also { detachLocked() }
        }
        notifyState()
        if (old != null) {
            val destroyed = runCatching { destroyShell(old.shell) }.isSuccess
            if (!destroyed && old.display != null) {
                runCatching { destroyDisplay(old.display) }
            }
        }
    }

    fun refreshReadiness() {
        notifyState()
    }

    private fun onDeath(deadShell: S, deadDisplay: D?) {
        val died = synchronized(lock) {
            val current = active ?: return
            if (current.shell === deadShell || (deadDisplay != null && current.display === deadDisplay)) {
                detachLocked()
                true
            } else {
                false
            }
        }
        if (died) notifyState()
    }

    private fun detachLocked() {
        unlinkShell?.let { runCatching(it) }
        unlinkDisplay?.let { runCatching(it) }
        unlinkShell = null
        unlinkDisplay = null
        active = null
    }

    private fun notifyState() {
        val snapshot = synchronized(lock) { active }
        val displayReady = currentDisplay() != null
        onStateChanged(snapshot, displayReady)
    }
}
