package io.github.nanomuse.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EmailScrubTest {
    @Test
    fun `redacts an English one-time code`() {
        val text = "Your verification code is 482913. It expires in ten minutes."
        assertEquals("Your verification code is [REDACTED-CODE]. It expires in ten minutes.", EmailScrub.scrub(text))
    }

    @Test
    fun `redacts a Chinese one-time code`() {
        assertEquals("验证码：[REDACTED-CODE]，5分钟内有效", EmailScrub.scrub("验证码：123456，5分钟内有效"))
    }

    @Test
    fun `redacts a password reset link`() {
        val text = "Reset it here: https://example.com/account/reset?token=abc123 now."
        assertEquals("Reset it here: [REDACTED-LINK] now.", EmailScrub.scrub(text))
    }

    @Test
    fun `leaves ordinary mail alone`() {
        val text = "Lunch at noon? The report is attached."
        assertEquals(text, EmailScrub.scrub(text))
        assertFalse(EmailScrub.scrub("See https://example.com/news?id=7").contains("REDACTED"))
    }
}
