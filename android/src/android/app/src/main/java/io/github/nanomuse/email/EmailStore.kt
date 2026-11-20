package io.github.nanomuse.email

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class EmailSaveResult { OK, ENCRYPTION_UNAVAILABLE }

data class EmailTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
)

/**
 * The mailboxes. Their settings live as one JSON list in a plain preference file; each
 * authorization code or OAuth token bundle lives in EncryptedSharedPreferences under its
 * account id and is only ever read here.
 *
 * [configured] deliberately touches only the plain file: the agent reads it on every turn
 * while building the tool list, and opening the encrypted store means Keystore I/O.
 */
class EmailStore(
    private val prefs: SharedPreferences,
    private val secretProvider: () -> SharedPreferences?,
) {
    constructor(prefs: SharedPreferences, secrets: SharedPreferences?) : this(prefs, { secrets })

    private var secrets: SharedPreferences? = null

    private fun secretStore(): SharedPreferences? {
        secrets?.let { return it }
        val store = secretProvider() ?: return null
        secrets = store
        return store
    }

    @Synchronized
    fun accounts(): List<EmailConfig> {
        migratePlainIfNeeded()
        return readAccounts()
    }

    fun account(id: String): EmailConfig? = accounts().firstOrNull { it.id == id }

    fun code(id: String): String? {
        val store = secretStore() ?: return null
        store.getString(codeKey(id), null)?.let { return it }
        if (id == LEGACY_ID) {
            // The single mailbox of the previous version kept its code under the old key.
            val old = store.getString(KEY_LEGACY_CODE, null) ?: return null
            store.edit().putString(codeKey(id), old).remove(KEY_LEGACY_CODE).commit()
            return old
        }
        return null
    }

    @Synchronized
    fun save(
        account: EmailConfig,
        code: String?,
        tokens: EmailTokens? = null,
    ): EmailSaveResult {
        val current = accounts().toMutableList()
        val id = account.id.ifBlank { UUID.randomUUID().toString() }
        val existing = current.firstOrNull { it.id == id }
        var normalized = account.normalized().copy(id = id)
        when (normalized.auth) {
            EmailAuth.PASSWORD -> {
                if (!code.isNullOrBlank()) {
                    val store = secretStore() ?: return EmailSaveResult.ENCRYPTION_UNAVAILABLE
                    if (!store.edit().putString(codeKey(id), code).commit()) {
                        return EmailSaveResult.ENCRYPTION_UNAVAILABLE
                    }
                    normalized = normalized.copy(hasCode = true)
                } else {
                    normalized = normalized.copy(hasCode = existing?.hasCode ?: account.hasCode)
                }
                if (existing?.hasToken == true) {
                    secretStore()?.edit()?.remove(tokensKey(id))?.commit()
                }
                normalized = normalized.copy(hasToken = false)
            }
            EmailAuth.MICROSOFT -> {
                if (tokens != null) {
                    val store = secretStore() ?: return EmailSaveResult.ENCRYPTION_UNAVAILABLE
                    if (!store.edit().putString(tokensKey(id), encodeTokens(tokens)).commit()) {
                        return EmailSaveResult.ENCRYPTION_UNAVAILABLE
                    }
                    normalized = normalized.copy(hasToken = true)
                } else {
                    normalized = normalized.copy(hasToken = account.hasToken || existing?.hasToken == true)
                }
                if (existing?.hasCode == true) {
                    secretStore()?.edit()?.remove(codeKey(id))?.commit()
                }
                normalized = normalized.copy(hasCode = false)
            }
        }
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) current[index] = normalized else current += normalized
        writeAccounts(current)
        return EmailSaveResult.OK
    }

    @Synchronized
    fun remove(id: String) {
        writeAccounts(accounts().filterNot { it.id == id })
        secretStore()?.edit()?.remove(codeKey(id))?.remove(tokensKey(id))?.commit()
        bump()
    }

    fun tokens(id: String): EmailTokens? {
        val raw = secretStore()?.getString(tokensKey(id), null) ?: return null
        val item = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val access = item.optString("access", "")
        if (access.isBlank()) return null
        return EmailTokens(
            accessToken = access,
            refreshToken = item.optString("refresh", "").ifBlank { null },
            expiresAtMs = item.optLong("expires_at", 0L),
        )
    }

    @Synchronized
    fun saveTokens(id: String, tokens: EmailTokens): Boolean {
        val store = secretStore() ?: return false
        if (!store.edit().putString(tokensKey(id), encodeTokens(tokens)).commit()) return false
        val current = accounts().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(hasToken = true)
            writeAccounts(current)
        } else {
            bump()
        }
        return true
    }

    @Synchronized
    fun clearTokens(id: String) {
        secretStore()?.edit()?.remove(tokensKey(id))?.commit()
        val current = accounts().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(hasToken = false, verified = false, enabled = false)
            writeAccounts(current)
        } else {
            bump()
        }
    }

    @Synchronized
    fun setVerified(id: String, verified: Boolean) {
        val current = accounts().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        current[index] = current[index].copy(verified = verified, enabled = verified)
        writeAccounts(current)
    }

    /** Pause or resume a verified mailbox without deleting its server settings or credentials. */
    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val current = accounts().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = current[index]
        val next = enabled && target.verified && target.complete
        current[index] = target.copy(enabled = next)
        writeAccounts(current)
    }

    fun anyConfigured(): Boolean = accounts().any { it.enabled && it.verified && it.complete }

    /** A conversation can switch the mailboxes off without disconnecting them. */
    fun isEnabledForSession(sessionId: String): Boolean =
        prefs.getBoolean(sessionKey(sessionId), true)

    fun setEnabledForSession(sessionId: String, enabled: Boolean) {
        prefs.edit().putBoolean(sessionKey(sessionId), enabled).commit()
        bump()
    }

    fun clearSession(sessionId: String) {
        if (sessionId.isBlank()) return
        prefs.edit().remove(sessionKey(sessionId)).commit()
        bump()
    }

    private fun readAccounts(): List<EmailConfig> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return parseAccounts(raw)
    }

    private fun writeAccounts(accounts: List<EmailConfig>) {
        prefs.edit().putString(KEY_ACCOUNTS, toJson(accounts)).commit()
        bump()
    }

    /**
     * The previous version stored one mailbox in flat keys. Move it into the list once,
     * without opening the encrypted store: its code is picked up lazily by [code].
     */
    private fun migratePlainIfNeeded() {
        if (prefs.getBoolean(KEY_SCHEMA_V2, false)) return
        if (prefs.contains(KEY_ACCOUNTS)) {
            prefs.edit().putBoolean(KEY_SCHEMA_V2, true).commit()
            return
        }
        val address = prefs.getString(KEY_ADDRESS, "").orEmpty()
        val imap = prefs.getString(KEY_IMAP_HOST, "").orEmpty()
        val smtp = prefs.getString(KEY_SMTP_HOST, "").orEmpty()
        val migrated = if (address.isNotBlank() || imap.isNotBlank() || smtp.isNotBlank()) {
            listOf(
                EmailConfig(
                    id = LEGACY_ID,
                    enabled = prefs.getBoolean(KEY_ENABLED, false),
                    verified = prefs.getBoolean(KEY_VERIFIED, false),
                    address = address,
                    imapHost = imap,
                    imapPort = prefs.getInt(KEY_IMAP_PORT, 993),
                    smtpHost = smtp,
                    smtpPort = prefs.getInt(KEY_SMTP_PORT, 587),
                    smtpStarttls = prefs.getBoolean(KEY_STARTTLS, true),
                    hasCode = prefs.getBoolean(KEY_HAS_CODE, false),
                ),
            )
        } else {
            emptyList()
        }
        writeAccounts(migrated)
        prefs.edit()
            .remove(KEY_ENABLED)
            .remove(KEY_VERIFIED)
            .remove(KEY_HAS_CODE)
            .remove(KEY_ADDRESS)
            .remove(KEY_IMAP_HOST)
            .remove(KEY_IMAP_PORT)
            .remove(KEY_SMTP_HOST)
            .remove(KEY_SMTP_PORT)
            .remove(KEY_STARTTLS)
            .putBoolean(KEY_SCHEMA_V2, true)
            .commit()
    }

    companion object {
        private const val FILE = "nanomuse_email"
        private const val SECRET_FILE = "nanomuse_email_secrets"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_SCHEMA_V2 = "schema_v2"
        private const val LEGACY_ID = "legacy"
        private const val KEY_LEGACY_CODE = "authorization_code"

        // The flat keys of the single-mailbox version, read once by migration.
        private const val KEY_ENABLED = "enabled"
        private const val KEY_VERIFIED = "verified"
        private const val KEY_HAS_CODE = "has_code"
        private const val KEY_ADDRESS = "address"
        private const val KEY_IMAP_HOST = "imap_host"
        private const val KEY_IMAP_PORT = "imap_port"
        private const val KEY_SMTP_HOST = "smtp_host"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_STARTTLS = "smtp_starttls"

        private val _version = MutableStateFlow(0L)

        /** Bumped whenever mailbox configuration or a session override changes. */
        val version: StateFlow<Long> = _version.asStateFlow()

        private fun bump() {
            _version.value = _version.value + 1L
        }

        fun forContext(context: Context): EmailStore {
            val app = context.applicationContext
            val lazySecrets = lazy { EncryptedPrefsFactory.createOrNull(app, SECRET_FILE) }
            return EmailStore(
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE),
                { lazySecrets.value },
            )
        }

        /** Cheap and Keystore-free: the tool list and the settings row both use it. */
        fun configured(context: Context): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_ACCOUNTS, null)
            if (raw != null) return parseAccounts(raw).any { it.enabled && it.verified && it.complete }
            // The flat keys, before the first migration.
            return prefs.getBoolean(KEY_ENABLED, false) &&
                prefs.getBoolean(KEY_VERIFIED, false) &&
                prefs.getBoolean(KEY_HAS_CODE, false) &&
                !prefs.getString(KEY_ADDRESS, "").isNullOrBlank() &&
                !prefs.getString(KEY_IMAP_HOST, "").isNullOrBlank() &&
                !prefs.getString(KEY_SMTP_HOST, "").isNullOrBlank()
        }

        fun sessionEnabled(context: Context, sessionId: String): Boolean {
            if (sessionId.isBlank()) return true
            val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return prefs.getBoolean(sessionKey(sessionId), true)
        }

        fun configuredForSession(context: Context, sessionId: String): Boolean =
            configured(context) && sessionEnabled(context, sessionId)

        fun clearSession(context: Context, sessionId: String) {
            if (sessionId.isBlank()) return
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .remove(sessionKey(sessionId))
                .commit()
            bump()
        }

        private fun sessionKey(sessionId: String): String = "session::$sessionId"

        private fun codeKey(id: String): String = "code::$id"

        private fun tokensKey(id: String): String = "tokens::$id"

        private fun encodeTokens(tokens: EmailTokens): String =
            JSONObject().apply {
                put("access", tokens.accessToken)
                put("refresh", tokens.refreshToken ?: "")
                put("expires_at", tokens.expiresAtMs)
            }.toString()

        private fun toJson(accounts: List<EmailConfig>): String {
            val array = JSONArray()
            for (account in accounts) {
                array.put(
                    JSONObject().apply {
                        put("id", account.id)
                        put("label", account.label)
                        put("enabled", account.enabled)
                        put("verified", account.verified)
                        put("address", account.address)
                        put("imap_host", account.imapHost)
                        put("imap_port", account.imapPort)
                        put("smtp_host", account.smtpHost)
                        put("smtp_port", account.smtpPort)
                        put("smtp_starttls", account.smtpStarttls)
                        put("auth", account.auth.name)
                        put("client_id", account.clientId)
                        put("has_code", account.hasCode)
                        put("has_token", account.hasToken)
                    },
                )
            }
            return array.toString()
        }

        private fun parseAccounts(raw: String): List<EmailConfig> {
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            val out = mutableListOf<EmailConfig>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val address = item.optString("address", "")
                val imapHost = item.optString("imap_host", "")
                val parsedAuth = runCatching {
                    EmailAuth.valueOf(item.optString("auth", "PASSWORD").uppercase(Locale.US))
                }.getOrDefault(EmailAuth.PASSWORD)
                val isOutlook = imapHost.equals("outlook.office365.com", ignoreCase = true) ||
                    MailPresets.forAddress(address)?.auth == EmailAuth.MICROSOFT
                val auth = if (isOutlook) {
                    EmailAuth.MICROSOFT
                } else {
                    parsedAuth
                }
                out += EmailConfig(
                    id = item.optString("id", ""),
                    label = item.optString("label", ""),
                    enabled = item.optBoolean("enabled", false),
                    verified = item.optBoolean("verified", false),
                    address = address,
                    imapHost = imapHost,
                    imapPort = item.optInt("imap_port", 993),
                    smtpHost = item.optString("smtp_host", ""),
                    smtpPort = item.optInt("smtp_port", 587),
                    smtpStarttls = item.optBoolean("smtp_starttls", true),
                    auth = auth,
                    clientId = item.optString("client_id", "").ifBlank {
                        if (auth == EmailAuth.MICROSOFT) MicrosoftOAuth.DEFAULT_CLIENT_ID else ""
                    },
                    hasCode = if (auth == EmailAuth.MICROSOFT) false else item.optBoolean("has_code", false),
                    hasToken = item.optBoolean("has_token", false),
                )
            }
            return out
        }
    }
}
