package io.github.nanomuse.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.openminis.app.accessibility.MinisAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Submits a gesture from the hands' worker, without leaving queued input behind after Stop. */
object HandsGestureDispatcher {
    fun dispatch(
        svc: MinisAccessibilityService,
        path: Path,
        durationMs: Long,
        isStopped: () -> Boolean,
        canDispatch: () -> Boolean = { true },
    ): Boolean {
        if (isStopped() || Thread.currentThread().isInterrupted) return false
        val main = Handler(Looper.getMainLooper())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchQueued(
            isStopped = isStopped,
            post = { main.post(it) },
            remove = { main.removeCallbacks(it) },
            canDispatch = canDispatch,
            submit = { finish ->
                svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = finish(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = finish(false)
                }, main)
            },
        )
    }

    /**
     * The submission check runs on the input queue too: Stop can arrive after [post].
     * Abandoning a wait removes the queued submission and makes even a dequeued one inert.
     * A gesture already handed to Android may finish; cancelling it must not inject a new tap.
     */
    internal fun dispatchQueued(
        isStopped: () -> Boolean,
        post: (Runnable) -> Boolean,
        remove: (Runnable) -> Unit,
        submit: ((Boolean) -> Unit) -> Boolean,
        timeoutMs: Long = 5_000L,
        canDispatch: () -> Boolean = { true },
    ): Boolean {
        val worker = Thread.currentThread()
        if (isStopped() || worker.isInterrupted) return false
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<Boolean>?>(null)
        val submissionLock = Any()
        var abandoned = false

        fun finish(outcome: Result<Boolean>) {
            if (result.compareAndSet(null, outcome)) done.countDown()
        }

        val request = Runnable {
            synchronized(submissionLock) {
                if (abandoned || worker.isInterrupted || isStopped()) {
                    finish(Result.success(false))
                    return@Runnable
                }
                try {
                    if (!canDispatch()) {
                        finish(Result.success(false))
                        return@Runnable
                    }
                    if (!submit { finish(Result.success(it)) }) finish(Result.success(false))
                } catch (error: Exception) {
                    finish(Result.failure(error))
                }
            }
        }

        try {
            if (!post(request)) return false
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
            if (isStopped() || worker.isInterrupted) return false
            return result.get()?.getOrThrow() ?: false
        } finally {
            synchronized(submissionLock) { abandoned = true }
            remove(request)
        }
    }
}
