package io.github.nanomuse.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * 以 shell 身份 (uid 2000) 运行的守护进程入口。
 * 由 Shizuku、root 或 USB 启动:
 * `CLASSPATH=<apk> app_process /system/bin io.github.nanomuse.shell.HelperProcess <pkg> <uid> <nonce>`
 */
object HelperProcess {
    private const val TAG = "NanoMuseHelper"
    private const val PER_USER_RANGE = 100_000

    @JvmStatic
    fun main(args: Array<String>) {
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
        val service = ShellService(allowedUid = appUid)
        service.recoverMuted()

        deliver(service, packageName, appUid, nonce)
        Looper.loop()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun deliver(service: ShellService, packageName: String, appUid: Int, nonce: String) {
        val authority = HelperHandoff.authority(packageName)
        val userId = appUid / PER_USER_RANGE
        try {
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
                return
            }

            val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
            if (info == null || info.packageName != packageName) {
                Log.e(TAG, "provider $authority does not belong to $packageName")
                return
            }

            val provider = holder.javaClass.getField("provider").get(holder)!!
            val extras = Bundle().apply {
                putString(HelperHandoff.KEY_NONCE, nonce)
                putBinder(HelperHandoff.KEY_BINDER, service)
                putInt(HelperHandoff.KEY_UID, Process.myUid())
                putInt(HelperHandoff.KEY_PID, Process.myPid())
            }

            val callMethod = provider.javaClass.methods.firstOrNull {
                it.name == "call" && it.parameterTypes.size == 5
            }
            if (callMethod != null) {
                val context = ShellContext.create()
                val attrSource = context.attributionSource
                callMethod.invoke(provider, attrSource, authority, HelperHandoff.METHOD_ATTACH, null, extras)
                Log.i(TAG, "successfully attached to $packageName")
            } else {
                Log.e(TAG, "provider.call method not found")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "deliver failed: ${t.message}", t)
        }
    }
}
