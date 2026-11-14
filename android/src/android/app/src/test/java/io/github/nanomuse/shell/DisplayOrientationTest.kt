package io.github.nanomuse.shell

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayOrientationTest {

    @Test
    fun `a landscape app on an upright display turns the display sideways`() {
        assertEquals(
            1,
            DisplayOrientation.rotation(bufferWidth = 1080, bufferHeight = 2400, current = 0, appWidth = 1920, appHeight = 1080),
        )
    }

    @Test
    fun `a landscape app already on a sideways display needs nothing`() {
        assertNull(
            DisplayOrientation.rotation(bufferWidth = 1080, bufferHeight = 2400, current = 1, appWidth = 1920, appHeight = 1080),
        )
    }

    @Test
    fun `a portrait app on a sideways display turns back upright`() {
        assertEquals(
            0,
            DisplayOrientation.rotation(bufferWidth = 1080, bufferHeight = 2400, current = 1, appWidth = 1080, appHeight = 1920),
        )
    }

    @Test
    fun `a landscape buffer needs no rotation for a landscape app`() {
        assertNull(
            DisplayOrientation.rotation(bufferWidth = 2400, bufferHeight = 1080, current = 0, appWidth = 1920, appHeight = 1080),
        )
    }

    @Test
    fun `a square buffer is left alone`() {
        assertNull(
            DisplayOrientation.rotation(bufferWidth = 1000, bufferHeight = 1000, current = 0, appWidth = 1920, appHeight = 1080),
        )
    }

    @Test
    fun `without an app window the requested orientation decides`() {
        assertEquals(
            1,
            DisplayOrientation.rotation(
                bufferWidth = 1080, bufferHeight = 2400, current = 0, appWidth = 0, appHeight = 0,
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
            ),
        )
        assertNull(
            DisplayOrientation.rotation(
                bufferWidth = 1080, bufferHeight = 2400, current = 0, appWidth = 0, appHeight = 0,
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ),
        )
        // 没说方向时不去猜。
        assertNull(
            DisplayOrientation.rotation(
                bufferWidth = 1080, bufferHeight = 2400, current = 0, appWidth = 0, appHeight = 0,
            ),
        )
    }
}
