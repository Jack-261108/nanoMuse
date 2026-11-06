package io.github.nanomuse.shell.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.shell.setup.BuiltinShellManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 监听手机开机完成广播，自动恢复无线调试并重新拉起特权守护进程。
 */
class BootRestoreReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        AppLogger.info(TAG, "received boot / package replaced event ($action), triggering builtin shell recovery...")
        val pendingResult = goAsync()

        scope.launch {
            try {
                val manager = BuiltinShellManager.instance(context)
                manager.need()
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "boot recovery failed: ${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootRestoreReceiver"
    }
}
