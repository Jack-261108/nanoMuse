package io.github.nanomuse.shell

object HelperHandoff {
    const val METHOD_ATTACH = "attach"
    const val KEY_BINDER = "binder"
    const val KEY_NONCE = "nonce"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_CLIENT_TOKEN = "client_token"
    const val KEY_ACCEPTED = "ok"

    fun authority(packageName: String): String = "$packageName.shell.helper"
}
