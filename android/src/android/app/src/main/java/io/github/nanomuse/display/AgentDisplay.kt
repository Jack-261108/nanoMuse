package io.github.nanomuse.display

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.nanomuse.shell.INanoMuseShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * Agent 专用的虚拟屏。画面输出到本进程的 ImageReader，不依赖无障碍截图接口。
 * 帧处理限速到 <= 10 帧/秒。
 */
class AgentDisplay(
    private val shell: INanoMuseShell,
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    dpi: Int,
    private val displayManager: DisplayManager,
) {
    private val thread = HandlerThread("nanomuse-agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(bufferWidth, bufferHeight, PixelFormat.RGBA_8888, 2)

    val displayId: Int

    // ImageReader 始终是建屏时的物理尺寸；应用的逻辑坐标随虚拟屏旋转，与主屏方向无关。
    private val geometry get() = DisplayFrameGeometry(
        bufferWidth, bufferHeight,
        displayManager.getDisplay(displayId)?.rotation ?: 0,
    )
    val width: Int get() = geometry.width
    val height: Int get() = geometry.height

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** 实时画面: 限速 <= 10 帧/秒，缩到短边 <= 360 并转正。 */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // 带行填充的整帧，宽 = rowStride / pixelStride
    private var hasFrame = false
    private var frameGeometry = DisplayFrameGeometry(bufferWidth, bufferHeight, 0)
    /** 虚拟屏生产者的单调时钟纳秒时间戳，与整帧和缩略帧一起在 frameLock 下发布。 */
    private var frameTimestampNanos = 0L
    private var lastProcessedAt = 0L
    private var pending = false
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        val id = try {
            shell.createDisplay(reader.surface, bufferWidth, bufferHeight, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
        reader.setOnImageAvailableListener({ schedule() }, handler)
        // 建屏期间可能已经有帧排进来；先有正确的屏号，再处理这些帧。
        handler.post { schedule() }
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val capturedGeometry = geometry
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp!!.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                // 限速或线程调度会延后消费，不能把复制位图的时刻记成帧时刻。
                frameTimestampNanos = image.timestamp
                frameGeometry = capturedGeometry
                _frames.value = uprightDisplayFrame(
                    bmp, capturedGeometry, capturedGeometry.previewWidth(PREVIEW_SHORT_SIDE), paint,
                )
            }
        } finally {
            image.close()
        }
    }

    /** 最新一帧的整幅 PNG；尚未收到画面时返回 null。 */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** 最新已发布帧的单调时钟纳秒时间戳。 */
    fun lastFrameAt(): Long? = synchronized(frameLock) {
        frameTimestampNanos.takeIf { !released && it > 0 }
    }

    /** 原始尺寸、方向正确的画面副本，调用方负责 recycle。 */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else uprightDisplayFrame(bmp, frameGeometry, paint = paint)
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val PREVIEW_SHORT_SIDE = 360
        const val FRAME_INTERVAL_MS = 100L
    }
}
