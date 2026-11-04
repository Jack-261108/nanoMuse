package io.github.nanomuse.shell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.lang.reflect.Method

/**
 * shell 进程直接访问系统剪贴板。
 * 绕开 ClipboardManager 中 shell 进程在部分系统上无法通过 ContentProvider 查询 Settings 的限制。
 */
@SuppressLint("PrivateApi")
internal class ShellClipboard(private val context: Context) {
    private val manager: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }
            .newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val service: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }
    private val getClip by lazy { method("getPrimaryClip") }
    private val getDescription by lazy { method("getPrimaryClipDescription") }
    private val hasClip by lazy { method("hasPrimaryClip") }

    val primaryClip: ClipData?
        get() = getClip.let {
            if (it == null) runCatching { manager.primaryClip }.getOrNull()
            else runCatching { call(it) as ClipData? }.getOrNull()
        }

    val primaryClipDescription: ClipDescription?
        get() = getDescription.let {
            if (it == null) runCatching { manager.primaryClipDescription }.getOrNull()
            else runCatching { call(it) as ClipDescription? }.getOrNull()
        }

    fun hasPrimaryClip(): Boolean =
        hasClip.let {
            if (it == null) runCatching { manager.hasPrimaryClip() }.getOrDefault(false)
            else runCatching { call(it) as Boolean }.getOrDefault(false)
        }

    fun setPrimaryClip(clip: ClipData) {
        manager.setPrimaryClip(clip)
    }

    fun clearPrimaryClip() {
        manager.clearPrimaryClip()
    }

    private fun method(name: String): Method? {
        if (service == null) return null
        val type = runCatching { Class.forName("android.content.IClipboard") }.getOrNull() ?: return null
        val s = String::class.java
        val i = Int::class.javaPrimitiveType!!
        val candidates = listOf(
            arrayOf(s, s, i, i),
            arrayOf(s, s, i),
            arrayOf(s, s, s, s, i, i, Boolean::class.javaPrimitiveType!!),
        )
        return candidates.firstNotNullOfOrNull { runCatching { type.getMethod(name, *it) }.getOrNull() }
    }

    private fun call(method: Method): Any? {
        val userId = Process.myUid() / 100_000
        val args: Array<Any?> = when (method.parameterTypes.size) {
            7 -> arrayOf(ShellContext.SHELL_PACKAGE, ShellContext.SHELL_PACKAGE, "", null, userId, 0, false)
            4 -> arrayOf(ShellContext.SHELL_PACKAGE, null, userId, 0)
            else -> arrayOf(ShellContext.SHELL_PACKAGE, null, userId)
        }
        return method.invoke(service, *args)
    }
}
