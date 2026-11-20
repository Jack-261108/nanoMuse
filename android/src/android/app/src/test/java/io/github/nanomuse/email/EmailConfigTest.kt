package io.github.nanomuse.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailConfigTest {
    @Test
    fun `matches common chinese and global mail domains`() {
        assertEquals("qq", MailPresets.forAddress("alice@qq.com")?.id)
        assertEquals("qq", MailPresets.forAddress("alice@foxmail.com")?.id)
        assertEquals("exmail", MailPresets.forAddress("alice@exmail.qq.com")?.id)
        assertEquals("163", MailPresets.forAddress("alice@163.com")?.id)
        assertEquals("126", MailPresets.forAddress("alice@126.com")?.id)
        assertEquals("imap.yeah.net", MailPresets.forAddress("alice@yeah.net")?.imapHost)
        assertEquals("aliyun", MailPresets.forAddress("alice@aliyun.com")?.id)
        assertEquals("feishu", MailPresets.forAddress("alice@feishu.cn")?.id)
        assertEquals("sina", MailPresets.forAddress("alice@sina.com")?.id)
        assertEquals("imap.sina.cn", MailPresets.forAddress("alice@sina.cn")?.imapHost)
        assertEquals("gmail", MailPresets.forAddress("alice@gmail.com")?.id)
        assertEquals("outlook", MailPresets.forAddress("alice@outlook.com")?.id)
        assertEquals("outlook", MailPresets.forAddress("alice@hotmail.com")?.id)
        assertEquals("icloud", MailPresets.forAddress("alice@icloud.com")?.id)
        assertEquals("yahoo", MailPresets.forAddress("alice@yahoo.com")?.id)
        assertEquals("fastmail", MailPresets.forAddress("alice@fastmail.com")?.id)
        assertNull(MailPresets.forAddress("alice@company.internal"))
        assertNull(MailPresets.forAddress("not-an-email"))
    }

    @Test
    fun `picks provider help by auth and server`() {
        assertEquals(MailHelpKind.MICROSOFT, MailPresets.helpFor("outlook.office365.com", EmailAuth.MICROSOFT))
        assertEquals(MailHelpKind.QQ, MailPresets.helpFor("imap.qq.com", EmailAuth.PASSWORD))
        assertEquals(MailHelpKind.QQ, MailPresets.helpFor("imap.exmail.qq.com", EmailAuth.PASSWORD))
        assertEquals(MailHelpKind.NETEASE, MailPresets.helpFor("imap.163.com", EmailAuth.PASSWORD))
        assertEquals(MailHelpKind.NETEASE, MailPresets.helpFor("imap.126.com", EmailAuth.PASSWORD))
        assertEquals(MailHelpKind.GMAIL, MailPresets.helpFor("imap.gmail.com", EmailAuth.PASSWORD))
        assertEquals(MailHelpKind.APP_PASSWORD, MailPresets.helpFor("imap.fastmail.com", EmailAuth.PASSWORD))
    }

    @Test
    fun `validates email address shape`() {
        val base = EmailConfig(
            address = "ok@example.com",
            imapHost = "imap.example.com",
            smtpHost = "smtp.example.com",
            hasCode = true,
        )
        assertTrue(base.issues().isEmpty())
        assertTrue(base.copy(address = "missing-at").issues().contains(EmailIssue.ADDRESS))
        assertTrue(base.copy(address = "@example.com").issues().contains(EmailIssue.ADDRESS))
        assertTrue(base.copy(address = "user@nodot").issues().contains(EmailIssue.ADDRESS))
        assertNotNull(MailPresets.all.firstOrNull { it.id == "126" })
        assertFalse(MailPresets.all.first { it.id == "163" }.smtpStarttls)
    }
}
