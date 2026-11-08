package io.github.nanomuse.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsScreenStateTest {
    @Test
    fun `new virtual display can choose an app without a screenshot`() {
        val state = HandsScreenState.classify(true, false, false, false, false)

        assertEquals(HandsScreenState.EMPTY, state)
        assertTrue(state.allows(HandsAction.OpenApp("Browser")))
        assertTrue(state.allows(HandsAction.AskUser("Which app?")))
        assertTrue(state.allows(HandsAction.Status(false, "No matching app")))
        assertTrue(HandsPrompt.turnText(1, null, emptyScreen = true).contains("no screenshot"))
    }

    @Test
    fun `empty or stale black background is not a protected application`() {
        assertEquals(HandsScreenState.EMPTY, HandsScreenState.classify(true, false, false, true, true))
        assertEquals(HandsScreenState.EMPTY, HandsScreenState.classify(true, false, false, true, false))
        assertEquals(HandsScreenState.WAITING_FOR_FRAME, HandsScreenState.classify(true, true, false, true, true))
        assertEquals(HandsScreenState.WAITING_FOR_FRAME, HandsScreenState.classify(true, true, true, false, false))
        assertEquals(HandsScreenState.PROTECTED, HandsScreenState.classify(true, true, true, true, true))
    }

    @Test
    fun `empty display rejects screen gestures text and navigation`() {
        val point = HandsAction.Point(100, 200)
        val unsafe = listOf(
            HandsAction.Click(point, "Next"), HandsAction.DoubleTap(point, "Next"),
            HandsAction.LongPress(point, "Next"), HandsAction.Swipe(point, point),
            HandsAction.Scroll("down"), HandsAction.InputText("hello", "search"),
            HandsAction.KeyboardEnter, HandsAction.Back, HandsAction.Home,
            HandsAction.Wait(1), HandsAction.TakeOver("Sign in"),
        )

        unsafe.forEach { assertFalse(it.describe(), HandsScreenState.EMPTY.allows(it)) }
    }

    @Test
    fun `visible frame enables normal actions and main screen keeps protection detection`() {
        val state = HandsScreenState.classify(true, true, true, true, false)
        assertEquals(HandsScreenState.READY, state)
        assertTrue(state.allows(HandsAction.Click(HandsAction.Point(100, 200), "Search")))
        assertEquals(HandsScreenState.PROTECTED, HandsScreenState.classify(false, true, false, true, true))
    }
}
