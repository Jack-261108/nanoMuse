package io.github.nanomuse.email

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EmailStoreTest {
    private fun store(secrets: SharedPreferences? = FakePrefs()): Pair<EmailStore, FakePrefs> {
        val prefs = FakePrefs()
        return EmailStore(prefs, secrets) to prefs
    }

    private fun config(address: String = "someone@example.com", label: String = "") = EmailConfig(
        label = label,
        enabled = true,
        verified = false,
        address = address,
        imapHost = "imap.example.com",
        imapPort = 993,
        smtpHost = "smtp.example.com",
        smtpPort = 587,
        smtpStarttls = true,
    )

    private fun context(prefs: SharedPreferences) = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `saves several mailboxes with their own codes`() {
        val (store, _) = store()
        assertEquals(EmailSaveResult.OK, store.save(config("a@x.com", "work"), "code-a"))
        assertEquals(EmailSaveResult.OK, store.save(config("b@y.com", "personal"), "code-b"))
        val accounts = store.accounts()
        assertEquals(2, accounts.size)
        assertEquals(setOf("work", "personal"), accounts.map { it.label }.toSet())
        val a = accounts.first { it.address == "a@x.com" }
        val b = accounts.first { it.address == "b@y.com" }
        assertEquals("code-a", store.code(a.id))
        assertEquals("code-b", store.code(b.id))
        assertTrue(a.hasCode && b.hasCode)
    }

    @Test
    fun `refuses to save a code when encryption is unavailable`() {
        val (store, _) = store(secrets = null)
        assertEquals(EmailSaveResult.ENCRYPTION_UNAVAILABLE, store.save(config(), "code-123"))
        assertTrue(store.accounts().isEmpty())
    }

    @Test
    fun `keeps the existing code when the field is blank`() {
        val (store, _) = store()
        store.save(config(), "code-123")
        val id = store.accounts().single().id
        store.save(store.account(id)!!.copy(smtpPort = 465, smtpStarttls = false), null)
        assertEquals("code-123", store.code(id))
        assertEquals(465, store.account(id)!!.smtpPort)
    }

    @Test
    fun `remove deletes the mailbox and its code`() {
        val (store, _) = store()
        store.save(config("a@x.com"), "code-a")
        store.save(config("b@y.com"), "code-b")
        val a = store.accounts().first { it.address == "a@x.com" }
        store.remove(a.id)
        assertEquals(1, store.accounts().size)
        assertNull(store.account(a.id))
        assertNull(store.code(a.id))
    }

    @Test
    fun `migrates the previous single mailbox and its code`() {
        val prefs = FakePrefs()
        val secrets = FakePrefs()
        prefs.edit()
            .putBoolean("enabled", true)
            .putBoolean("verified", true)
            .putBoolean("has_code", true)
            .putString("address", "old@example.com")
            .putString("imap_host", "imap.example.com")
            .putString("smtp_host", "smtp.example.com")
            .apply()
        secrets.edit().putString("authorization_code", "old-code").apply()
        val store = EmailStore(prefs, secrets)
        val account = store.accounts().single()
        assertEquals("old@example.com", account.address)
        assertTrue(account.enabled && account.verified && account.hasCode)
        assertEquals("old-code", store.code(account.id))
    }

    @Test
    fun `storing oauth tokens marks the account usable and switching auth cleans up stale credentials`() {
        val (store, _) = store()
        val tokens = EmailTokens("access-1", "refresh-1", System.currentTimeMillis() + 3_600_000)
        assertEquals(
            EmailSaveResult.OK,
            store.save(config("work@example.com").copy(auth = EmailAuth.MICROSOFT, clientId = "client-1"), null, tokens),
        )
        val id = store.accounts().single().id
        val saved = store.accounts().single()
        assertTrue(saved.hasToken)
        assertFalse(saved.hasCode)
        assertTrue(saved.complete)
        assertEquals("access-1", store.tokens(id)!!.accessToken)

        // Switching to password removes stored OAuth tokens.
        store.save(saved.copy(auth = EmailAuth.PASSWORD), "app-pass", null)
        assertNull(store.tokens(id))
        assertEquals("app-pass", store.code(id))
        assertTrue(store.account(id)!!.hasCode)
        assertFalse(store.account(id)!!.hasToken)

        // Switching back to Microsoft removes stored authorization code.
        store.save(store.account(id)!!.copy(auth = EmailAuth.MICROSOFT), null, tokens)
        assertNull(store.code(id))
        assertEquals("access-1", store.tokens(id)!!.accessToken)
    }

    @Test
    fun `pauses and resumes a verified mailbox without losing credentials`() {
        val prefs = FakePrefs()
        val store = EmailStore(prefs, FakePrefs())
        val context = context(prefs)
        store.save(config("a@x.com"), "code-a")
        val id = store.accounts().single().id
        store.setVerified(id, true)
        assertTrue(EmailStore.configured(context))

        store.setEnabled(id, false)
        assertFalse(EmailStore.configured(context))
        assertTrue(store.account(id)!!.verified)
        assertEquals("code-a", store.code(id))

        store.setEnabled(id, true)
        assertTrue(EmailStore.configured(context))
    }

    @Test
    fun `parses microsoft auth safely under turkish locale`() {
        val previous = Locale.getDefault()
        val prefs = FakePrefs()
        prefs.edit().putString(
            "accounts",
            """[{"id":"1","address":"a@outlook.com","imap_host":"outlook.office365.com","smtp_host":"smtp.office365.com","auth":"microsoft","has_token":true,"enabled":true,"verified":true}]""",
        ).apply()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val store = EmailStore(prefs, FakePrefs())
            assertEquals(EmailAuth.MICROSOFT, store.accounts().single().auth)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `normalizes outlook accounts with password auth to microsoft auth`() {
        val prefs = FakePrefs()
        prefs.edit().putString(
            "accounts",
            """[{"id":"1","address":"user@outlook.com","imap_host":"outlook.office365.com","smtp_host":"smtp.office365.com","auth":"PASSWORD","has_code":true,"enabled":true}]""",
        ).apply()
        val store = EmailStore(prefs, FakePrefs())
        val account = store.accounts().single()
        assertEquals(EmailAuth.MICROSOFT, account.auth)
        assertEquals(MicrosoftOAuth.DEFAULT_CLIENT_ID, account.clientId)
        assertFalse(account.hasCode)
    }

    @Test
    fun `oauth account needs a sign-in`() {
        val account = config().copy(auth = EmailAuth.MICROSOFT)
        assertTrue(account.issues().contains(EmailIssue.TOKEN))
        val ready = account.copy(hasToken = true)
        assertFalse(ready.issues().contains(EmailIssue.TOKEN))
        assertTrue(ready.complete)
    }

    @Test
    fun `configured follows the verified mailboxes`() {
        val prefs = FakePrefs()
        val store = EmailStore(prefs, FakePrefs())
        val context = context(prefs)
        store.save(config("a@x.com"), "code-a")
        val id = store.accounts().single().id
        store.setVerified(id, true)
        assertTrue(EmailStore.configured(context))
        store.setVerified(id, false)
        assertFalse(EmailStore.configured(context))
    }

    @Test
    fun `a chat can switch the mailboxes off and clear session resets it`() {
        val prefs = FakePrefs()
        val store = EmailStore(prefs, FakePrefs())
        val context = context(prefs)
        assertTrue(store.isEnabledForSession("chat-1"))
        val before = EmailStore.version.value
        store.setEnabledForSession("chat-1", false)
        assertTrue(EmailStore.version.value > before)
        assertFalse(store.isEnabledForSession("chat-1"))
        assertTrue(store.isEnabledForSession("chat-2"))
        EmailStore.clearSession(context, "chat-1")
        assertTrue(store.isEnabledForSession("chat-1"))
    }

    @Test
    fun `configured for session follows the chat switch`() {
        val prefs = FakePrefs()
        val store = EmailStore(prefs, FakePrefs())
        val context = context(prefs)
        store.save(config("a@x.com"), "code-a")
        val id = store.accounts().single().id
        store.setVerified(id, true)
        assertTrue(EmailStore.configuredForSession(context, "chat-1"))
        store.setEnabledForSession("chat-1", false)
        assertTrue(EmailStore.configured(context))
        assertFalse(EmailStore.configuredForSession(context, "chat-1"))
        assertTrue(EmailStore.configuredForSession(context, "chat-2"))
    }
}
