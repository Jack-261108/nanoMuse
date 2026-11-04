package io.github.nanomuse.display

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPreviewTest {

    @Test
    fun testFrameAspectRatio() {
        // Standard phone 9:16 ~ 0.5625
        assertEquals(0.5625f, frameAspectRatio(1080, 1920), 0.001f)
        // Ratio clamps to [0.3f, 1.8f]
        assertEquals(0.3f, frameAspectRatio(100, 1000), 0.001f)
        assertEquals(1.8f, frameAspectRatio(2000, 100), 0.001f)
        // Invalid height fallback
        assertEquals(0.5f, frameAspectRatio(1080, 0), 0.001f)
    }

    @Test
    fun testPreviewFramesDeduplication() = runBlocking {
        // Feed duplicate numbers: 1, 1, 2, 2, 3, 3
        val input = listOf(1, 1, 2, 2, 3, 3)
        val result = input.asFlow().previewFrames(intervalMs = 1L, same = { a, b -> a == b }).toList()
        assertEquals(listOf(1, 2, 3), result)
    }
}
