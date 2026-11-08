package io.github.nanomuse.shell

import java.util.concurrent.atomic.AtomicBoolean

/** Explicit stop and client Binder death may arrive together. Cleanup and exit happen once. */
internal class HelperLifetime(private val cleanup: () -> Unit, private val exit: () -> Unit) {
    private val stopped = AtomicBoolean(false)

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try {
            cleanup()
        } finally {
            exit()
        }
    }
}
