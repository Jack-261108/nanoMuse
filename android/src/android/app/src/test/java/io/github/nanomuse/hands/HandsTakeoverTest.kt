package io.github.nanomuse.hands

import android.content.Context
import com.openminis.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class HandsTakeoverTest {
    private val context = mock(Context::class.java)
    private val operator = HandsOperator(context)
    private val target = mock(HandsDisplayTarget::class.java)

    private fun movable() {
        `when`(target.onTakeoverStart()).thenReturn(true)
        `when`(target.onTakeoverEnd()).thenReturn(true)
    }

    @Test
    fun `payment continue consumes the old tap and requires another observation`() {
        movable()
        `when`(context.getString(R.string.nm_hands_payment_takeover)).thenReturn("Handle payment")

        val gate = operator.manualPayment(target, "Pay") { true }

        assertTrue(gate.reobserve)
        assertTrue(gate.notice.orEmpty().contains("verify the result"))
        verify(target, never()).tap(anyInt(), anyInt())
        inOrder(target).apply {
            verify(target).onTakeoverStart()
            verify(target).onTakeoverEnd()
        }
    }

    @Test
    fun `failed move to main does not show takeover or try to restore an unrelated task`() {
        `when`(context.getString(R.string.nm_hands_takeover_move_failed)).thenReturn("move failed")
        var asked = false

        assertThrows(IllegalStateException::class.java) {
            operator.takeOver(target, "Sign in") { asked = true; true }
        }

        assertFalse(asked)
        verify(target, never()).onTakeoverEnd()
    }

    @Test
    fun `failed return to virtual display prevents continuation`() {
        `when`(target.onTakeoverStart()).thenReturn(true)
        `when`(context.getString(R.string.nm_hands_takeover_restore_failed)).thenReturn("restore failed")

        assertThrows(IllegalStateException::class.java) {
            operator.takeOver(target, "Sign in") { true }
        }
    }

    @Test
    fun `interruption still restores the operated task`() {
        movable()

        assertThrows(InterruptedException::class.java) {
            operator.takeOver(target, "Sign in") { throw InterruptedException("stop") }
        }

        verify(target).onTakeoverEnd()
    }

    @Test
    fun `stop during takeover restores the task without resuming automation`() {
        movable()

        assertThrows(InterruptedException::class.java) {
            operator.takeOver(target, "Sign in") { operator.requestStop(); true }
        }

        verify(target).onTakeoverEnd()
    }
}
