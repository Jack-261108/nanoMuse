package io.github.nanomuse.hands

import io.github.nanomuse.hands.HandsPipLayout.Bounds
import io.github.nanomuse.hands.HandsPipLayout.Corner
import io.github.nanomuse.hands.HandsPipLayout.Motion
import io.github.nanomuse.hands.HandsPipLayout.ScreenArea
import io.github.nanomuse.hands.HandsPipLayout.Spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HandsPipLayoutTest {

    @Test
    fun `fit uses baseWidth when aspect fits in available space`() {
        val size = HandsPipLayout.fit(
            baseWidth = 300f,
            aspect = 2f,
            availableWidth = 1000f,
            availableHeight = 2000f,
            inset = 12f,
        )
        assertEquals(300f, size.width, 0.01f)
        assertEquals(588f, size.height, 0.01f)
    }

    @Test
    fun `fit shrinks proportionally when height limits and never clips`() {
        val size = HandsPipLayout.fit(
            baseWidth = 300f,
            aspect = 2f,
            availableWidth = 1000f,
            availableHeight = 400f,
            inset = 12f,
        )
        assertTrue(size.height <= 400f + 0.01f)
        assertTrue(size.width <= 300f + 0.01f)
        val contentRatio = (size.height - 12f) / (size.width - 12f)
        assertEquals(2f, contentRatio, 0.01f)
    }

    @Test
    fun `fit shrinks when width limits`() {
        val size = HandsPipLayout.fit(
            baseWidth = 300f,
            aspect = 2f,
            availableWidth = 200f,
            availableHeight = 1000f,
            inset = 12f,
        )
        assertEquals(200f, size.width, 0.01f)
        assertEquals(388f, size.height, 0.01f)
    }

    @Test
    fun `fit preserves aspect across rotation in landscape`() {
        val size = HandsPipLayout.fit(
            baseWidth = 500f,
            aspect = 0.5f,
            availableWidth = 900f,
            availableHeight = 400f,
            inset = 10f,
        )
        assertEquals(500f, size.width, 0.01f)
        val contentRatio = (size.height - 10f) / (size.width - 10f)
        assertEquals(0.5f, contentRatio, 0.01f)
    }

    @Test
    fun `windowSize restores exact collapsed dimensions after expand-collapse round trip`() {
        val area = ScreenArea(width = 1080, height = 2400, insetTop = 96, insetBottom = 48)
        val collapsedBefore = HandsPipLayout.windowSize(
            expanded = false,
            landscapeFrame = false,
            aspect = 2400f / 1080f,
            area = area,
            marginPx = 36f,
            insetTotalPx = 18f,
            chromeHeightPx = 0f,
            collapsedPortraitWidthPx = 312f,
            expandedPortraitWidthPx = 648f,
            collapsedLandscapeWidthPx = 528f,
            expandedLandscapeWidthPx = 900f,
        )
        val expanded = HandsPipLayout.windowSize(
            expanded = true,
            landscapeFrame = false,
            aspect = 2400f / 1080f,
            area = area,
            marginPx = 36f,
            insetTotalPx = 24f,
            chromeHeightPx = 96f,
            collapsedPortraitWidthPx = 312f,
            expandedPortraitWidthPx = 648f,
            collapsedLandscapeWidthPx = 528f,
            expandedLandscapeWidthPx = 900f,
        )
        val collapsedAfter = HandsPipLayout.windowSize(
            expanded = false,
            landscapeFrame = false,
            aspect = 2400f / 1080f,
            area = area,
            marginPx = 36f,
            insetTotalPx = 18f,
            chromeHeightPx = 0f,
            collapsedPortraitWidthPx = 312f,
            expandedPortraitWidthPx = 648f,
            collapsedLandscapeWidthPx = 528f,
            expandedLandscapeWidthPx = 900f,
        )
        assertTrue(expanded.width > collapsedBefore.width)
        assertTrue(expanded.height > collapsedBefore.height)
        assertEquals(collapsedBefore, collapsedAfter)
    }

    @Test
    fun `fittedFrameSize preserves exact aspect ratio inside stage`() {
        val portrait = HandsPipLayout.fittedFrameSize(1080, 2400, 300, 800)
        assertEquals(300, portrait.width)
        assertEquals(667, portrait.height)

        val landscape = HandsPipLayout.fittedFrameSize(2400, 1080, 600, 600)
        assertEquals(600, landscape.width)
        assertEquals(270, landscape.height)
    }

    @Test
    fun `bounds respect safe area insets and clamp negative overshoot`() {
        val b = Bounds(
            areaWidth = 1000f,
            areaHeight = 2000f,
            pipWidth = 200f,
            pipHeight = 400f,
            margin = 36f,
            insetLeft = 10f,
            insetTop = 100f,
            insetRight = 20f,
            insetBottom = 50f,
        )
        assertEquals(46f, b.left, 0.01f)
        assertEquals(744f, b.right, 0.01f)
        assertEquals(136f, b.top, 0.01f)
        assertEquals(1514f, b.bottom, 0.01f)

        val oversized = Bounds(
            areaWidth = 100f,
            areaHeight = 200f,
            pipWidth = 300f,
            pipHeight = 400f,
            margin = 36f,
            insetTop = 50f,
        )
        assertEquals(oversized.left, oversized.right, 0.01f)
        assertEquals(oversized.top, oversized.bottom, 0.01f)
    }

    @Test
    fun `snapCorner uses window corner geometry and fling velocity even when expanded`() {
        val b = Bounds(
            areaWidth = 1080f,
            areaHeight = 2400f,
            pipWidth = 648f,
            pipHeight = 1400f,
            margin = 36f,
            insetTop = 80f,
            insetBottom = 48f,
        )
        // 右边允许的最大 left 坐标是 1080 - 648 - 36 = 396；中心偏右一点（例如 x = 310）应直接吸附右侧角，不再错误弹回左侧
        val rightEdgeResting = b.snapCorner(x = 310f, y = 200f, vx = 0f, vy = 0f)
        assertEquals(Corner.TopEnd, rightEdgeResting)

        // 从左上角朝右下角甩动足够速度时投射到右下角
        val flung = b.snapCorner(x = 120f, y = 300f, vx = 1800f, vy = 2200f)
        assertEquals(Corner.BottomEnd, flung)
    }

    @Test
    fun `spring settles critically damped without overshoot`() {
        val spring = Spring()
        var motion = Motion(position = 0f, velocity = 0f)
        var maxPos = Float.NEGATIVE_INFINITY
        var steps = 0
        while (!spring.settled(motion, 500f) && steps < 300) {
            motion = spring.step(motion, 500f, 0.016f)
            if (motion.position > maxPos) maxPos = motion.position
            steps++
        }
        assertTrue(spring.settled(motion, 500f))
        assertTrue("expected no overshoot beyond target, got $maxPos", maxPos <= 502f)
        assertTrue(abs(motion.position - 500f) < 1f)
    }

    @Test
    fun `shouldSyncFrameResize blocks window overwrite while animating, dragging, or settling`() {
        assertFalse(
            HandsPipLayout.shouldSyncFrameResize(
                isDragging = false,
                isAnimatingSize = true,
                isSettling = false,
                currentWidth = 648,
                currentHeight = 1400,
                targetWidth = 312,
                targetHeight = 674,
                currentX = 732,
                currentY = 1600,
                clampedX = 396,
                clampedY = 900,
            ),
        )
        assertFalse(
            HandsPipLayout.shouldSyncFrameResize(
                isDragging = true,
                isAnimatingSize = false,
                isSettling = false,
                currentWidth = 312,
                currentHeight = 674,
                targetWidth = 312,
                targetHeight = 674,
                currentX = 10,
                currentY = 10,
                clampedX = 36,
                clampedY = 100,
            ),
        )
        assertFalse(
            HandsPipLayout.shouldSyncFrameResize(
                isDragging = false,
                isAnimatingSize = false,
                isSettling = true,
                currentWidth = 312,
                currentHeight = 674,
                targetWidth = 312,
                targetHeight = 674,
                currentX = 10,
                currentY = 10,
                clampedX = 36,
                clampedY = 100,
            ),
        )
        assertTrue(
            HandsPipLayout.shouldSyncFrameResize(
                isDragging = false,
                isAnimatingSize = false,
                isSettling = false,
                currentWidth = 312,
                currentHeight = 674,
                targetWidth = 648,
                targetHeight = 1400,
                currentX = 732,
                currentY = 1600,
                clampedX = 396,
                clampedY = 900,
            ),
        )
        assertFalse(
            HandsPipLayout.shouldSyncFrameResize(
                isDragging = false,
                isAnimatingSize = false,
                isSettling = false,
                currentWidth = 312,
                currentHeight = 674,
                targetWidth = 312,
                targetHeight = 674,
                currentX = 732,
                currentY = 1600,
                clampedX = 732,
                clampedY = 1600,
            ),
        )
    }

    @Test
    fun `shouldPassTouchToFrame routes control bar and border taps back to window collapse`() {
        assertTrue(HandsPipLayout.shouldPassTouchToFrame(operateNow = true, insideFrame = true))
        assertFalse(HandsPipLayout.shouldPassTouchToFrame(operateNow = true, insideFrame = false))
        assertFalse(HandsPipLayout.shouldPassTouchToFrame(operateNow = false, insideFrame = true))
        assertFalse(HandsPipLayout.shouldPassTouchToFrame(operateNow = false, insideFrame = false))
    }

    @Test
    fun `collapsed and expanded portrait sizes stay distinct when fitted`() {
        val area = ScreenArea(width = 1080, height = 2400, insetTop = 96, insetBottom = 48)
        val collapsed = HandsPipLayout.windowSize(
            expanded = false,
            landscapeFrame = false,
            aspect = 2.2f,
            area = area,
            marginPx = 36f,
            insetTotalPx = 18f,
            chromeHeightPx = 0f,
            collapsedPortraitWidthPx = 312f,
            expandedPortraitWidthPx = 648f,
            collapsedLandscapeWidthPx = 528f,
            expandedLandscapeWidthPx = 900f,
        )
        val expanded = HandsPipLayout.windowSize(
            expanded = true,
            landscapeFrame = false,
            aspect = 2.2f,
            area = area,
            marginPx = 36f,
            insetTotalPx = 24f,
            chromeHeightPx = 96f,
            collapsedPortraitWidthPx = 312f,
            expandedPortraitWidthPx = 648f,
            collapsedLandscapeWidthPx = 528f,
            expandedLandscapeWidthPx = 900f,
        )
        assertNotEquals(collapsed.width, expanded.width)
        assertNotEquals(collapsed.height, expanded.height)
    }
}
