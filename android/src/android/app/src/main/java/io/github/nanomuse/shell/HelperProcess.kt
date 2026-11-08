package io.github.nanomuse.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import kotlin.system.exitProcess

/**
 * 以 shell 身份 (uid 2000) 运行的守护进程入口。
 * 由 Shizuku、root 或 USB 启动:
 * `CLASSPATH=<apk> app_process /system/bin io.github.nanomuse.shell.HelperProcess <pkg> <uid> <nonce>`
 */
object HelperProcess {
    private const val TAG = "NanoMuseHelper"
    private const val PER_USER_RANGE = 100_000
    private var clientToken: IBinder? = null
    private var processLockFile: RandomAccessFile? = null
    private var processLock: FileLock? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.e(TAG, "the background helper requires Android 12 or later")
            exitProcess(2)
        }
        if (args.size < 2) {
            Log.e(TAG, "usage: HelperProcess <package> <uid> [nonce]")
            exitProcess(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        val nonce = if (args.size > 2 && args[2] != "-") {
            args[2]
        } else {
            runCatching { readLine() }.getOrNull().orEmpty()
        }

        Looper.prepareMainLooper()
        // A restarted app may launch while its old helper is still restoring audio. Serialize
        // the entire helper lifetime, including cleanup, without deleting the lock inode.
        val lockPath = File("/data/local/tmp", "nanomuse_helper_$appUid.lock")
        val lockFile = RandomAccessFile(lockPath, "rw")
        if (Process.myUid() == 0) Os.chown(lockPath.absolutePath, 2000, 2000)
        processLockFile = lockFile
        processLock = runCatching { lockFile.channel.tryLock() }.getOrNull()
        if (processLock == null) {
            lockFile.close()
            Log.w(TAG, "another helper is still active or cleaning up")
            exitProcess(3)
        }
        val service = ShellService(allowedUid = appUid, onDestroyed = { exitProcess(0) })

        if (!deliver(service, packageName, appUid, nonce)) {
            service.destroy()
            return
        }
        Looper.loop()
        service.destroy()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun deliver(service: ShellService, packageName: String, appUid: Int, nonce: String): Boolean {
        val authority = HelperHandoff.authority(packageName)
        val userId = appUid / PER_USER_RANGE
        return try {
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = Binder()
            val holder = am.javaClass.getMethod(
                "getContentProviderExternal",
                String::class.java,
                Int::class.javaPrimitiveType,
                IBinder::class.java,
                String::class.java,
            ).invoke(am, authority, userId, token, TAG)

            if (holder == null) {
                Log.e(TAG, "getContentProviderExternal returned null for $authority")
                return false
            }
            try {
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                    Log.e(TAG, "provider $authority does not belong to the requested app")
                    return false
                }
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(HelperHandoff.KEY_NONCE, nonce)
                    putBinder(HelperHandoff.KEY_BINDER, service)
                    putInt(HelperHandoff.KEY_UID, Process.myUid())
                    putInt(HelperHandoff.KEY_PID, Process.myPid())
                }
                val callMethod = provider.javaClass.methods.firstOrNull {
                    it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                } ?: return false
                val attrSource = AttributionSource.Builder(Process.myUid())
                    .setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = callMethod.invoke(provider, attrSource, authority, HelperHandoff.METHOD_ATTACH, null, extras) as? Bundle
                if (result?.getBoolean(HelperHandoff.KEY_ACCEPTED) != true) return false
                val client = result.getBinder(HelperHandoff.KEY_CLIENT_TOKEN) ?: return false
                client.linkToDeath({ service.destroy() }, 0)
                clientToken = client
                if (!client.isBinderAlive) return false
                service.onClientAttached()
                Log.i(TAG, "successfully attached to $packageName")
                true
            } finally {
                // Holding the external reference would keep the app process alive and mask its death.
                am.javaClass.getMethod(
                    "removeContentProviderExternalAsUser", String::class.java, IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(am, authority, token, userId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "deliver failed: ${t.message}", t)
            false
        }
    }
}
