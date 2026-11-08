package io.github.nanomuse.shell

/** Reject late handoffs after stop or after another start superseded the old one. */
internal class HelperHandoffState {
    private var expected: String? = null
    fun expect(nonce: String) { expected = nonce }
    fun accepts(nonce: String?): Boolean = nonce != null && nonce == expected
    fun cancel(nonce: String? = null) {
        if (nonce == null || nonce == expected) expected = null
    }
}
