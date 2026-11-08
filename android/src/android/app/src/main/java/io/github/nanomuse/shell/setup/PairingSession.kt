package io.github.nanomuse.shell.setup

/** A notification reply or screen reading belongs to exactly one open pairing session. */
internal class PairingSession {
    private var generation = java.util.concurrent.ThreadLocalRandom.current().nextLong()
    private var open = false

    @Synchronized fun begin(): Long {
        open = true
        return ++generation
    }

    @Synchronized fun accepts(token: Long): Boolean = open && generation == token
    @Synchronized fun end() { open = false; generation++ }
}
