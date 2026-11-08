package io.github.nanomuse.hands

import io.github.nanomuse.hands.HandsGesturePlacement.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsGesturePlacementTest {
    private val screen = Box(6, 30, 394, 770)
    private val capsule = Box(50, 36, 350, 100)

    @Test
    fun `a stroke clear of the capsule leaves Stop where the person expects it`() {
        assertEquals(capsule, HandsGesturePlacement.choose(capsule, screen, 200, 300, 200, 600, 8))
    }

    @Test
    fun `a tap under Stop moves the capsule without disabling touch`() {
        val placed = HandsGesturePlacement.choose(capsule, screen, 320, 60, 320, 60, 8)
        assertNotNull(placed)
        assertTrue(screen.contains(placed!!))
        assertFalse(HandsGesturePlacement.intersects(placed, 320, 60, 320, 60, 8))
    }

    @Test
    fun `the middle and end of a swipe are protected even when its start is clear`() {
        assertTrue(HandsGesturePlacement.intersects(capsule, 200, 0, 200, 500, 8))
        assertTrue(HandsGesturePlacement.intersects(capsule, 200, 500, 200, 60, 8))
        val placed = HandsGesturePlacement.choose(capsule, screen, 200, 0, 200, 500, 8)
        assertNotNull(placed)
        assertFalse(HandsGesturePlacement.intersects(placed!!, 200, 0, 200, 500, 8))
    }

    @Test
    fun `a diagonal avoids the actual line rather than its whole bounding box`() {
        val corner = Box(10, 100, 50, 140)
        assertFalse(HandsGesturePlacement.intersects(corner, 0, 0, 200, 200, 8))
        assertTrue(HandsGesturePlacement.intersects(Box(80, 80, 120, 120), 200, 200, 0, 0, 8))
    }

    @Test
    fun `near misses inside the safety margin count as collisions`() {
        assertTrue(HandsGesturePlacement.intersects(capsule, 0, 108, 400, 108, 8))
        assertFalse(HandsGesturePlacement.intersects(capsule, 0, 109, 400, 109, 8))
    }

    @Test
    fun `a full height swipe can move a small capsule to the side`() {
        val placed = HandsGesturePlacement.choose(Box(150, 36, 250, 100), screen, 200, 0, 200, 800, 8)
        assertNotNull(placed)
        assertTrue(screen.contains(placed!!))
        assertFalse(HandsGesturePlacement.intersects(placed, 200, 0, 200, 800, 8))
    }

    @Test
    fun `a stroke with no safe location is refused`() {
        assertNull(HandsGesturePlacement.choose(capsule, screen, 200, 0, 200, 800, 8))
    }

    @Test
    fun `unmeasured and oversized windows never authorize injection`() {
        assertNull(HandsGesturePlacement.choose(Box(0, 0, 0, 64), screen, 0, 0, 0, 0, 8))
        assertNull(HandsGesturePlacement.choose(Box(0, 0, 500, 64), screen, 0, 0, 0, 0, 8))
        assertNull(HandsGesturePlacement.choose(capsule, Box(0, 0, 400, 40), 0, 0, 0, 0, 8))
    }

    @Test
    fun `a dragged window is brought inside the visible screen`() {
        val placed = HandsGesturePlacement.choose(Box(-100, 100, 200, 164), screen, 200, 300, 200, 600, 8)
        assertNotNull(placed)
        assertTrue(screen.contains(placed!!))
    }

    @Test
    fun `a full width capsule can still allow a stroke below it`() {
        val fullWidth = Box(0, 36, 400, 100)
        assertEquals(
            fullWidth,
            HandsGesturePlacement.choose(fullWidth, Box(0, 30, 400, 770), 200, 300, 200, 600, 8),
        )
    }
}
