package io.github.nanomuse.hands

/** Keeps the touchable capsule clear of an entire injected stroke, including its end. */
internal object HandsGesturePlacement {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        fun contains(other: Box): Boolean = other.left >= left && other.top >= top &&
            other.right <= right && other.bottom <= bottom
    }

    fun intersects(box: Box, x1: Int, y1: Int, x2: Int, y2: Int, margin: Int): Boolean {
        var entry = 0.0
        var exit = 1.0
        fun axis(start: Int, end: Int, low: Int, high: Int): Boolean {
            val delta = end.toDouble() - start
            if (delta == 0.0) return start >= low && start <= high
            val a = (low.toDouble() - start) / delta
            val b = (high.toDouble() - start) / delta
            entry = maxOf(entry, minOf(a, b))
            exit = minOf(exit, maxOf(a, b))
            return entry <= exit
        }
        return axis(x1, x2, box.left - margin, box.right + margin) &&
            axis(y1, y2, box.top - margin, box.bottom + margin)
    }

    fun choose(
        current: Box, screen: Box, x1: Int, y1: Int, x2: Int, y2: Int, margin: Int,
    ): Box? {
        if (current.width <= 0 || current.height <= 0 ||
            current.width > screen.width || current.height > screen.height
        ) return null
        val left = screen.left
        val right = screen.right - current.width
        val centerX = (left + right) / 2
        val top = screen.top
        val bottom = screen.bottom - current.height
        val centerY = (top + bottom) / 2
        val candidates = listOf(
            current,
            Box(centerX, top, centerX + current.width, top + current.height),
            Box(centerX, bottom, centerX + current.width, bottom + current.height),
            Box(left, centerY, left + current.width, centerY + current.height),
            Box(right, centerY, right + current.width, centerY + current.height),
            Box(left, top, left + current.width, top + current.height),
            Box(right, top, right + current.width, top + current.height),
            Box(left, bottom, left + current.width, bottom + current.height),
            Box(right, bottom, right + current.width, bottom + current.height),
        )
        return candidates.firstOrNull {
            screen.contains(it) && !intersects(it, x1, y1, x2, y2, margin)
        }
    }
}
