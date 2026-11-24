package io.github.nanomuse.hands

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One background screen's control handover.
 *
 * The agent holds the screen while [State.Automatic]. A person asking for the preview waits
 * for the action already in flight, then owns every touch until handing it back. The agent
 * never runs an ordinary screen action while the person owns it, and a change of owner bumps
 * [version] so a model plan made from the old screen can be discarded.
 */
class HandsControl {
    enum class State { Automatic, Requesting, Manual, Closed }

    enum class BeginResult { Acquired, Cancelled, TimedOut, BusyOrClosed }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    @Volatile private var _current = State.Automatic
    @Volatile private var _version = 0L
    @Volatile private var _lastUserInputAtNanos = 0L
    private var activeActions = 0
    private var activeUserInputs = 0
    private var handingBack = false
    private val automaticDepth = ThreadLocal.withInitial { 0 }
    private val _state = MutableStateFlow(State.Automatic)

    val state: StateFlow<State> = _state.asStateFlow()

    val current: State get() = _current

    /** Bumped when a person asks for control and again when they hand it back. */
    val version: Long get() = _version

    /** Monotonic timestamp (`System.nanoTime()`) when the last [userInput] finished, or 0 if none. */
    val lastUserInputAtNanos: Long get() = _lastUserInputAtNanos

    /**
     * Waits while a handover is active and returns [version] from the same lock acquisition that
     * confirmed [State.Automatic]; null when [stopped], interrupted, or [State.Closed].
     */
    fun awaitAutomaticVersion(stopped: () -> Boolean = { false }): Long? {
        while (true) {
            lock.withLock {
                if (stopped()) return null
                when (_current) {
                    State.Automatic -> return _version
                    State.Closed -> return null
                    State.Requesting, State.Manual -> {
                        try {
                            changed.await(50, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return null
                        }
                    }
                }
            }
        }
    }

    /** True while the agent may act. Waits during a handover; false when stopped or closed. */
    fun awaitAutomatic(stopped: () -> Boolean = { false }): Boolean =
        awaitAutomaticVersion(stopped) != null

    /**
     * Runs one agent action. A person's takeover waits for this action to finish; once the
     * takeover has been requested, new top-level actions return null without touching the screen.
     * Nested calls on the same thread may complete the in-flight action unless [State.Closed].
     */
    fun <T> automatic(block: () -> T): T? {
        val depth = automaticDepth.get() ?: 0
        lock.withLock {
            val allowed = _current == State.Automatic || (depth > 0 && _current == State.Requesting)
            if (!allowed) return null
            activeActions++
        }
        automaticDepth.set(depth + 1)
        try {
            return block()
        } finally {
            if (depth == 0) automaticDepth.remove() else automaticDepth.set(depth)
            lock.withLock {
                activeActions--
                if (activeActions == 0) changed.signalAll()
            }
        }
    }

    /** A person asks for control and waits for the agent's current action to finish. */
    fun begin(timeoutMs: Long = 10_000L): BeginResult {
        var myVersion = -1L
        var handed = false
        try {
            return lock.withLock {
                if (_current != State.Automatic) return@withLock BeginResult.BusyOrClosed
                _current = State.Requesting
                _version++
                myVersion = _version
                publishLocked()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
                while (activeActions > 0 && _current == State.Requesting && _version == myVersion) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0L) break
                    changed.awaitNanos(left)
                }
                when {
                    _current == State.Requesting && _version == myVersion && activeActions == 0 -> {
                        _current = State.Manual
                        publishLocked()
                        handed = true
                        BeginResult.Acquired
                    }
                    _current == State.Requesting && _version == myVersion -> {
                        _current = State.Automatic
                        _version++
                        publishLocked()
                        changed.signalAll()
                        BeginResult.TimedOut
                    }
                    _current == State.Closed -> BeginResult.BusyOrClosed
                    else -> BeginResult.Cancelled
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return BeginResult.Cancelled
        } finally {
            if (myVersion >= 0L && !handed) {
                lock.withLock {
                    if (_current == State.Requesting && _version == myVersion) {
                        _current = State.Automatic
                        _version++
                        publishLocked()
                        changed.signalAll()
                    }
                }
            }
        }
    }

    /** Cancels a takeover still waiting for the agent's current action; never blocks on user input. */
    fun cancelRequest(): Boolean = lock.withLock {
        if (_current != State.Requesting) return@withLock false
        _current = State.Automatic
        _version++
        publishLocked()
        changed.signalAll()
        true
    }

    /** Returns control to the agent, or cancels a request still waiting for the current action. */
    fun handBack(): Boolean = lock.withLock {
        when (_current) {
            State.Requesting -> {
                _current = State.Automatic
                _version++
                publishLocked()
                changed.signalAll()
                true
            }
            State.Manual -> {
                if (handingBack) {
                    var interrupted = false
                    try {
                        while (handingBack && _current == State.Manual) {
                            try {
                                changed.await()
                            } catch (_: InterruptedException) {
                                interrupted = true
                            }
                        }
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                    return@withLock false
                }
                handingBack = true
                var interrupted = false
                try {
                    while (activeUserInputs > 0 && _current == State.Manual) {
                        try {
                            changed.await()
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    if (_current == State.Manual) {
                        _current = State.Automatic
                        _version++
                        publishLocked()
                        true
                    } else {
                        false
                    }
                } finally {
                    handingBack = false
                    changed.signalAll()
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
            State.Automatic, State.Closed -> false
        }
    }

    /**
     * Runs a person's input only while they own the screen. Tracks in-flight inputs without
     * holding the lock across I/O or Binder IPC so hand-back waits cleanly without stalling callers.
     * When [expectedVersion] is given, an input queued before a handover change is discarded.
     */
    fun <T> userInput(expectedVersion: Long? = null, block: () -> T): T? {
        lock.withLock {
            if (_current != State.Manual || handingBack) return null
            if (expectedVersion != null && _version != expectedVersion) return null
            activeUserInputs++
        }
        try {
            return block()
        } finally {
            lock.withLock {
                activeUserInputs--
                _lastUserInputAtNanos = System.nanoTime()
                if (activeUserInputs == 0) changed.signalAll()
            }
        }
    }

    fun close() = lock.withLock {
        if (_current != State.Closed) {
            _current = State.Closed
            handingBack = false
            _version++
            publishLocked()
            changed.signalAll()
        }
    }

    private fun publishLocked() {
        _state.value = _current
    }
}
