package io.github.nanomuse.display

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFrameGeometryTest {

    @Test
    fun `a quarter turn swaps the logical size`() {
        val upright = DisplayFrameGeometry(1080, 2400, rotation = 0)
        assertEquals(1080, upright.width)
        assertEquals(2400, upright.height)

        val quarter = DisplayFrameGeometry(1080, 2400, rotation = 1)
        assertEquals(2400, quarter.width)
        assertEquals(1080, quarter.height)

        val threeQuarters = DisplayFrameGeometry(1080, 2400, rotation = 3)
        assertEquals(2400, threeQuarters.width)
        assertEquals(1080, threeQuarters.height)
    }

    @Test
    fun `half turn keeps the logical size`() {
        val half = DisplayFrameGeometry(1080, 2400, rotation = 2)
        assertEquals(1080, half.width)
        assertEquals(2400, half.height)
    }

    @Test
    fun `preview width uses the short side`() {
        // 竖屏：短边是宽，预览宽就是短边本身。
        assertEquals(360, DisplayFrameGeometry(1080, 2400, rotation = 0).previewWidth(360))
        // 横屏：短边是高，预览宽按比例放大，保持整屏完整。
        assertEquals(800, DisplayFrameGeometry(1080, 2400, rotation = 1).previewWidth(360))
    }

    @Test
    fun `preview size never exceeds the maximum width and keeps the ratio`() {
        val (w, h) = DisplayFrameGeometry(1080, 2400, rotation = 0).previewSize(360)
        assertEquals(360, w)
        assertEquals(800, h)

        val (narrowW, narrowH) = DisplayFrameGeometry(1080, 2400, rotation = 0).previewSize(2000)
        assertEquals(1080, narrowW)
        assertEquals(2400, narrowH)
    }
}
