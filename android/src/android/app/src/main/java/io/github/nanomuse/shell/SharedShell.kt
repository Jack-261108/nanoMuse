package io.github.nanomuse.shell

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.shell.setup.BuiltinShellManager
import io.github.nanomuse.shell.recovery.PermissionCommands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One live helper, with handoffs scoped to the start that requested them. */
object SharedShell {
    private const val TAG = "SharedShell"
    private val _shell = MutableStateFlow<INanoMuseShell?>(null)
    val shell: StateFlow<INanoMuseShell?> = _shell.asStateFlow()
    private var deathRecipient: IBinder.DeathRecipient? = null
    private val handoff = HelperHandoffState()

    fun isReady(): Boolean = _shell.value?.asBinder()?.isBinderAlive == true

    @Synchronized
    fun prepareStart(nonce: String) { handoff.expect(nonce) }

    @Synchronized
    fun cancelStart(nonce: String? = null) { handoff.cancel(nonce) }

    @Synchronized
    fun setShell(instance: INanoMuseShell?, pid: Int = -1, uid: Int = -1, nonce: String? = null): Boolean {
        if (instance == null) {
            detach()
            return true
        }
        if (!handoff.accepts(nonce) || isReady()) return false
        val binder = instance.asBinder()
        if (!binder.isBinderAlive || !binder.pingBinder()) return false
        val recipient = IBinder.DeathRecipient {
            synchronized(this) {
                if (_shell.value?.asBinder() === binder) {
                    AppLogger.warning(TAG, "shell helper died")
                    detach()
                }
            }
        }
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: Exception) {
            return false
        }
        if (!binder.isBinderAlive) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
            return false
        }
        detach()
        deathRecipient = recipient
        handoff.cancel()
        _shell.value = instance
        AppLogger.info(TAG, "accepted shell helper (pid=$pid, uid=$uid)")
        return true
    }

    private fun detach() {
        val old = _shell.value
        val recipient = deathRecipient
        if (old != null && recipient != null) runCatching { old.asBinder().unlinkToDeath(recipient, 0) }
        deathRecipient = null
        _shell.value = null
    }

    /** All backends share the manager's start, stop and retry state. */
    fun startHelper(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        BuiltinShellManager.instance(context).need()
        return isReady()
    }

    fun getAdbCommand(context: Context): String {
        val nonce = java.util.UUID.randomUUID().toString()
        check(BuiltinShellManager.instance(context).prepareManualStart(nonce)) { "helper startup is unavailable" }
        return command(context, nonce)
    }

    internal fun command(context: Context, nonce: String): String {
        val apk = PermissionCommands.quote(context.applicationInfo.sourceDir)
        val pkg = PermissionCommands.quote(context.packageName)
        val process = PermissionCommands.quote("${context.packageName}:helper")
        return "CLASSPATH=$apk nohup app_process /system/bin --nice-name=$process " +
            "io.github.nanomuse.shell.HelperProcess $pkg ${Process.myUid()} '$nonce' </dev/null >/dev/null 2>&1 &"
    }

    fun release() {
        val instance = synchronized(this) {
            handoff.cancel()
            _shell.value.also { detach() }
        }
        // destroy performs cleanup and exits the privileged process.
        if (instance != null) runCatching { instance.destroy() }
    }
}
