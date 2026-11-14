package io.github.nanomuse.display

/**
 * 一帧画面的几何：缓冲尺寸是建屏时固定的，逻辑尺寸随虚拟屏旋转交换宽高。
 * rotation 用 [android.view.Surface] 的常量（0/1/2/3），这里写字面量以便在 JVM 上测。
 */
internal data class DisplayFrameGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotation: Int,
) {
    private val sideways get() = rotation == 1 || rotation == 3

    /** 应用看到的逻辑宽高。 */
    val width: Int get() = if (sideways) bufferHeight else bufferWidth
    val height: Int get() = if (sideways) bufferWidth else bufferHeight

    /** 预览画面缩到短边 [shortSide] 后的宽；横屏时短边是高，所以宽会超过 [shortSide]。 */
    fun previewWidth(shortSide: Int): Int =
        if (width > height) maxOf(1, (width.toLong() * shortSide / height).toInt()) else shortSide

    /** 缩到宽不超过 [maxWidth] 后的 (宽, 高)。 */
    fun previewSize(maxWidth: Int): Pair<Int, Int> {
        val w = minOf(maxWidth, width)
        return w to maxOf(1, (height.toLong() * w / width).toInt())
    }
}
