package io.github.nanomuse.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.system.Os
import android.util.Log
import io.github.nanomuse.hands.HandsDisplayUserService
import io.github.nanomuse.hands.HandsShellBridgeCalls
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import kotlin.system.exitProcess

/**
 * 以 shell 身份 (uid 2000，或 root uid 0) 运行的单一特权守护进程入口。
 * 同时提供 INanoMuseShell，以及在 Android 14+ 且 uid == 2000 下的 IHandsDisplay。
 * 启动方式:
 * `CLASSPATH=<apk> app_process /system/bin io.github.nanomuse.shell.HelperProcess <pkg> <uid> [nonce|-] [--wireless]`
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
            Log.e(TAG, "usage: HelperProcess <package> <uid> [nonce|-] [--wireless]")
            exitProcess(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        if (appUid < Process.FIRST_APPLICATION_UID) {
            Log.e(TAG, "invalid application uid: $appUid")
            exitProcess(2)
        }
        val nonce = if (args.size > 2 && args[2] != "-") {
            args[2]
        } else {
            runCatching { readLine() }.getOrNull().orEmpty()
        }
        if (nonce.isBlank()) {
            Log.e(TAG, "missing handoff nonce")
            exitProcess(2)
        }
        val wireless = args.size > 3 && args[3] == HelperHandoff.ARG_WIRELESS
        if (wireless && Process.myUid() != HelperHandoff.SHELL_UID) {
            Log.e(TAG, "wireless helper must run as shell uid 2000")
            exitProcess(2)
        }

        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        // A restarted app may launch while its old helper is still restoring audio. Serialize
        // the entire helper lifetime, including cleanup, without deleting the lock inode.
        val lockPath = File("/data/local/tmp", "nanomuse_helper_$appUid.lock")
        val lockFile = RandomAccessFile(lockPath, "rw")
        if (Process.myUid() == HelperHandoff.ROOT_UID) {
            Os.chown(lockPath.absolutePath, HelperHandoff.SHELL_UID, HelperHandoff.SHELL_UID)
        }
        processLockFile = lockFile
        processLock = runCatching { lockFile.channel.tryLock() }.getOrNull()
        if (processLock == null) {
            lockFile.close()
            Log.w(TAG, "another helper is still active or cleaning up")
            exitProcess(3)
        }

        val appContext = runCatching { resolveVerifiedAppContext(packageName, appUid) }.getOrElse {
            Log.e(TAG, "failed to verify target app package: ${it.message}", it)
            exitProcess(2)
        }

        val userId = appUid / PER_USER_RANGE
        val wirelessCalls = if (wireless) {
            val calls = runCatching { HandsShellBridgeCalls(userId) }.getOrElse {
                Log.e(TAG, "wireless settings bridge unavailable: ${it.message}", it)
                exitProcess(2)
            }
            if (!calls.wirelessEnabled()) {
                Log.w(TAG, "wireless debugging is disabled; refusing wireless helper startup")
                exitProcess(2)
            }
            calls
        } else null

        var displayService: HandsDisplayUserService? = null
        val service = ShellService(
            allowedUid = appUid,
            onDestroyed = { exitProcess(0) },
            extraCleanup = { displayService?.shutdown() },
        )
        if (Process.myUid() == HelperHandoff.SHELL_UID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            displayService = HandsDisplayUserService(appContext, onDestroy = { service.destroyInternal() }).also {
                it.wirelessAccess = wirelessCalls
            }
        }

        if (!deliver(service, displayService, packageName, appUid, nonce, wireless)) {
            service.destroyInternal()
            return
        }
        Looper.loop()
        service.destroyInternal()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun resolveVerifiedAppContext(packageName: String, appUid: Int): Context {
        ShellContext.create()
        val system = ShellContext.systemContext()
        val profile = UserHandle.getUserHandleForUid(appUid)
        val createForUser = Context::class.java.getMethod(
            "createPackageContextAsUser",
            String::class.java,
            Int::class.javaPrimitiveType,
            UserHandle::class.java,
        )
        val appContext = createForUser.invoke(system, packageName, 0, profile) as Context
        check(appContext.applicationInfo.uid == appUid) { "package uid mismatch" }
        val classpath = System.getenv("CLASSPATH")
        check(!classpath.isNullOrEmpty() && appContext.applicationInfo.sourceDir == classpath) {
            "helper CLASSPATH does not match installed package"
        }
        return appContext
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun deliver(
        service: ShellService,
        displayService: HandsDisplayUserService?,
        packageName: String,
        appUid: Int,
        nonce: String,
        wireless: Boolean,
    ): Boolean {
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
                    if (displayService != null) {
                        putBinder(HelperHandoff.KEY_DISPLAY_BINDER, displayService)
                    }
                    putInt(HelperHandoff.KEY_UID, Process.myUid())
                    putInt(HelperHandoff.KEY_PID, Process.myPid())
                    putBoolean(HelperHandoff.KEY_WIRELESS, wireless)
                }
                val callMethod = provider.javaClass.methods.firstOrNull {
                    it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                } ?: return false
                val attrSource = AttributionSource.Builder(Process.myUid())
                    .setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = callMethod.invoke(provider, attrSource, authority, HelperHandoff.METHOD_ATTACH, null, extras) as? Bundle
                if (result?.getBoolean(HelperHandoff.KEY_ACCEPTED) != true) return false
                val client = result.getBinder(HelperHandoff.KEY_CLIENT_TOKEN) ?: return false
                client.linkToDeath({ service.destroyInternal() }, 0)
                clientToken = client
                if (!client.isBinderAlive) return false
                service.onClientAttached()
                Log.i(TAG, "successfully attached to $packageName (display=${displayService != null}, wireless=$wireless)")
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
