package io.github.nanomuse.hands

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.openminis.app.offload.ShizukuManager
import io.github.nanomuse.shell.HelperHandoff
import io.github.nanomuse.shell.setup.BuiltinShellManager
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/** A separate display that cannot become the destination for the user's undirected key events. */
internal object HandsDisplayHost {
    private val inUse = AtomicBoolean()

    private fun shizukuReady(): Boolean {
        if (!ShizukuManager.isReady()) return false
        val snap = ShizukuManager.snapshot.value
        val uid = if (snap.uid >= 0) snap.uid else runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val version = if (snap.version >= 0) snap.version else runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        return uid == HelperHandoff.SHELL_UID && version >= Hands.BACKGROUND_MIN_SHIZUKU
    }

    fun isReady(): Boolean = HandsDisplayBridge.isReady() || shizukuReady()

    class Handle internal constructor(
        val display: Display,
        private val service: IHandsDisplay,
        private val authorized: () -> Boolean,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        fun isReady() = !closed.get() && service.asBinder().isBinderAlive && authorized()
        fun launch(pkg: String) = isReady() && service.launchPackage(pkg)
        fun tap(x: Int, y: Int) = isReady() && service.tap(x, y)
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = isReady() && service.swipe(x1, y1, x2, y2, ms)
        fun back() = isReady() && service.back()
        fun rememberTask(pkg: String) = isReady() && runCatching { service.rememberTask(pkg) }.getOrDefault(false)
        fun handOffToPhone() = isReady() && runCatching { service.handOffToPhone() }.getOrDefault(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    fun open(context: Context, surface: Surface, width: Int, height: Int, dpi: Int): Handle {
        check(Build.VERSION.SDK_INT >= Hands.BACKGROUND_MIN_SDK && isReady()) {
            "Android 14 or newer and an authorized background screen connection are required."
        }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Waiting for the background display would block the main thread." }
        check(inUse.compareAndSet(false, true)) { "A background display is already open." }
        val allocated = AtomicBoolean()
        var remote: IHandsDisplay? = null
        fun releaseLocal() {
            try {
                if (allocated.get()) remote?.close()
            } finally {
                inUse.set(false)
            }
        }
        try {
            val active = HandsDisplayBridge.current() ?: run {
                check(shizukuReady()) { "The background display service did not connect." }
                runBlocking { BuiltinShellManager.instance(context).ensureShellHelperForDisplay(10_000L) }
                checkNotNull(HandsDisplayBridge.current()) { "The background display service did not connect." }
            }
            remote = active
            val display = allocate(context, active, surface, width, height, dpi, allocated)
            return Handle(
                display = display,
                service = active,
                authorized = { HandsDisplayBridge.current()?.asBinder() === active.asBinder() },
                release = ::releaseLocal,
            )
        } catch (failure: Throwable) {
            runCatching { releaseLocal() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }

    private fun allocate(
        context: Context,
        remote: IHandsDisplay,
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        allocated: AtomicBoolean,
    ): Display {
        val session = remote.open(surface, width, height, dpi, Binder())
        allocated.set(true)
        check(session.size == 2 && session[1] >= 0 && session[0] > Display.DEFAULT_DISPLAY) {
            "Android did not allocate a separate screen and user profile."
        }
        return checkNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(session[0])).also {
            check(it.isValid) { "The background display was removed." }
        }
    }
}
