package io.github.nanomuse.shell

import java.security.SecureRandom
import java.util.Base64

/** How the current helper process was started, which determines display authorization rules. */
internal enum class HelperOrigin {
    WIRELESS,
    SHIZUKU,
    ROOT,
    MANUAL,
}

object HelperHandoff {
    const val METHOD_ATTACH = "attach"
    const val KEY_BINDER = "binder"
    const val KEY_DISPLAY_BINDER = "display_binder"
    const val KEY_NONCE = "nonce"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_WIRELESS = "wireless"
    const val KEY_CLIENT_TOKEN = "client_token"
    const val KEY_ACCEPTED = "ok"
    const val SHELL_UID = 2000
    const val ROOT_UID = 0
    const val ARG_WIRELESS = "--wireless"
    const val DEFAULT_TTL_MS = 90_000L

    private val random = SecureRandom()

    fun authority(packageName: String): String = "$packageName.shell.helper"

    fun generateNonce(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
