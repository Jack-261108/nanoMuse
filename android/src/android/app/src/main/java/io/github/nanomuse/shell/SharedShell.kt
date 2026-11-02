package io.github.nanomuse.shell

import android.content.Context
import android.os.IBinder
import android.os.Process
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 特权 Shell 助手的应用级单例连接管理器。
 */
object SharedShell {
    private const val TAG = "SharedShell"

    private val _shell = MutableStateFlow<INanoMuseShell?>(null)
    val shell: StateFlow<INanoMuseShell?> = _shell.asStateFlow()

    @Volatile private var helperPid: Int = -1
    @Volatile private var helperUid: Int = -1

    private val deathRecipient = IBinder.DeathRecipient {
        AppLogger.warning(TAG, "shell helper died")
        _shell.value = null
        helperPid = -1
        helperUid = -1
    }

    fun isReady(): Boolean = _shell.value != null

    fun setShell(instance: INanoMuseShell?, pid: Int = -1, uid: Int = -1) {
        val old = _shell.value
        if (old != null) {
            runCatching { old.asBinder().unlinkToDeath(deathRecipient, 0) }
        }
        _shell.value = instance
        helperPid = pid
        helperUid = uid
        if (instance != null) {
            runCatching { instance.asBinder().linkToDeath(deathRecipient, 0) }
        }
    }

    /**
     * 尝试启动特权助手：
     * 1. 优先检测并使用 Shizuku
     * 2. 其次尝试使用 su (root)
     */
    fun startHelper(context: Context): Boolean {
        if (isReady()) return true

        val apkPath = context.applicationInfo.sourceDir
        val pkg = context.packageName
        val uid = Process.myUid()
        val shellCmd = "CLASSPATH=$apkPath app_process /system/bin io.github.nanomuse.shell.HelperProcess $pkg $uid >/dev/null 2>&1 &"

        if (ShizukuManager.isReady()) {
            AppLogger.info(TAG, "starting shell helper via Shizuku...")
            Thread {
                ShizukuManager.runProcess(arrayOf("sh", "-c", shellCmd))
            }.start()
            return true
        }

        if (hasSu()) {
            AppLogger.info(TAG, "starting shell helper via su...")
            Thread {
                runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", shellCmd))
                    p.waitFor()
                }
            }.start()
            return true
        }

        return false
    }

    fun getAdbCommand(context: Context): String {
        val apkPath = context.applicationInfo.sourceDir
        val pkg = context.packageName
        val uid = Process.myUid()
        return "CLASSPATH=$apkPath nohup app_process /system/bin io.github.nanomuse.shell.HelperProcess $pkg $uid >/dev/null 2>&1 &"
    }

    private fun hasSu(): Boolean {
        val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su")
        return paths.any { File(it).canExecute() }
    }

    fun release() {
        val instance = _shell.value
        if (instance != null) {
            runCatching { instance.releaseAll() }
        }
        setShell(null)
    }
}
