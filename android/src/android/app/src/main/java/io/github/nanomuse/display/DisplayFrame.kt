package io.github.nanomuse.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * 把带行填充的原始缓冲画成方向正确的整帧，并按需缩放。
 *
 * 源矩形始终是 [DisplayFrameGeometry.bufferWidth] × [DisplayFrameGeometry.bufferHeight]，
 * 因此调用方传进来的位图即使宽等于 rowStride 也不会被横向拉伸。
 */
internal fun uprightDisplayFrame(
    buffer: Bitmap,
    geometry: DisplayFrameGeometry,
    maxWidth: Int = geometry.width,
    paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG),
): Bitmap {
    val (width, height) = geometry.previewSize(maxWidth)
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(result).apply {
        scale(width.toFloat() / geometry.width, height.toFloat() / geometry.height)
        when (geometry.rotation) {
            1 -> {
                translate(0f, geometry.bufferWidth.toFloat())
                rotate(-90f)
            }
            2 -> {
                translate(geometry.bufferWidth.toFloat(), geometry.bufferHeight.toFloat())
                rotate(180f)
            }
            3 -> {
                translate(geometry.bufferHeight.toFloat(), 0f)
                rotate(90f)
            }
        }
        val content = Rect(0, 0, geometry.bufferWidth, geometry.bufferHeight)
        drawBitmap(buffer, content, content, paint)
    }
    return result
}
