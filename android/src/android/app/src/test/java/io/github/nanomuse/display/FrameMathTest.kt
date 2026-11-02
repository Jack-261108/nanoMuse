package io.github.nanomuse.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMathTest {

    @Test
    fun testLumaGridUniform() {
        val width = 120
        val height = 240
        // Uniform grey: R=100, G=100, B=100 -> (100*3 + 100*6 + 100)/10 = 100
        val color = 0xff646464.toInt()
        val grid = FrameMath.lumaGrid(width, height) { _, _ -> color }
        assertEquals(FrameMath.GRID_W * FrameMath.GRID_H, grid.size)
        assertTrue(grid.all { it == 100 })
    }

    @Test
    fun testDiffersAndPageChanged() {
        val size = FrameMath.GRID_W * FrameMath.GRID_H
        val a = IntArray(size) { 50 }
        val b = IntArray(size) { 50 }
        assertFalse(FrameMath.differs(a, b))

        // Change 3 cells by 20 (> 16)
        b[0] = 75
        b[1] = 75
        b[2] = 75
        assertTrue(FrameMath.differs(a, b))
        assertFalse(FrameMath.pageChanged(a, b))

        // Change >= 25% cells
        val threshold = (size * 0.25).toInt() + 1
        for (i in 0 until threshold) {
            b[i] = 100
        }
        assertTrue(FrameMath.pageChanged(a, b))
    }

    @Test
    fun testIsBlack() {
        val width = 100
        val height = 200
        // Near-black: rgb <= 8
        assertTrue(FrameMath.isBlack(width, height) { _, _ -> 0xff050505.toInt() })
        // Not black: bright red
        assertFalse(FrameMath.isBlack(width, height) { _, _ -> 0xffff0000.toInt() })
    }
}
