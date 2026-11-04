package io.github.nanomuse.display

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/** 卡片上的小画面每秒最多几张。 */
const val PREVIEW_FPS = 5

/**
 * 卡片小画面要推的帧：每 [intervalMs] 至多一张，中间来的只留最新的；和上一张推过的一样 ([same]) 就不推。
 */
fun <T : Any> Flow<T>.previewFrames(
    intervalMs: Long = 1_000L / PREVIEW_FPS,
    same: (T, T) -> Boolean = { a, b -> a === b },
): Flow<T> = flow {
    var last: T? = null
    conflate().collect { f ->
        val prev = last
        if (prev != null && (prev === f || same(prev, f))) return@collect
        last = f
        emit(f)
        delay(intervalMs)
    }
}

/**
 * 比较两帧是否相同：检查是否同一对象、尺寸相同且像素相同。
 * 虚拟屏旧帧可能会被回收，因此必须先判断 [Bitmap.isRecycled]。
 */
fun sameFrame(a: Bitmap, b: Bitmap): Boolean =
    a === b || (!a.isRecycled && !b.isRecycled && a.width == b.width && a.height == b.height && a.sameAs(b))

/**
 * 画面的宽高比，夹在合理范围里（防止异常尺寸撑坏布局）。
 */
fun frameAspectRatio(b: Bitmap): Float = frameAspectRatio(b.width, b.height)

fun frameAspectRatio(width: Int, height: Int): Float =
    if (height <= 0) 0.5f else (width.toFloat() / height).coerceIn(0.3f, 1.8f)
