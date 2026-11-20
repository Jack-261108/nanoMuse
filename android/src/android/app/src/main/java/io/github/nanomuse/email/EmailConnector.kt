package io.github.nanomuse.email

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * The mailbox operations the tools call. Every network call runs on IO, with a 30 second
 * socket timeout, and every failure becomes one sentence that never contains the code.
 */
object EmailConnector {
    data class Outcome(val ok: Boolean, val message: String)

    data class ReplyContext(
        val subject: String,
        val inReplyTo: String?,
        val references: List<String>,
    )

    private const val BODY_CHARS = 3_000
    private const val TOTAL_CHARS = 30_000
    private const val CLIENT_SCAN_WINDOW = 30
    private const val MAX_MESSAGE_BYTES = 25L * 1024 * 1024
    private const val NOT_CONFIGURED =
        "The email connector is not set up. Open Settings, Connectors, Email and connect a mailbox."

    private val GMAIL_GROUPED_APP_PASSWORD = Regex("^[a-zA-Z]{4}(?: +[a-zA-Z]{4}){3}$")

    @Volatile
    internal var imapFactory: (EmailConfig) -> ImapClient = { config ->
        ImapClient(config.imapHost, config.imapPort)
    }

    @Volatile
    internal var smtpFactory: (EmailConfig) -> SmtpClient = { config ->
        SmtpClient(config.smtpHost, config.smtpPort, config.smtpStarttls)
    }

    private sealed class Credential {
        data class Password(val value: String) : Credential()
        data class Token(val value: String) : Credential()
    }

    internal fun normalizePassword(imapHost: String, password: String): String {
        val host = imapHost.trim().lowercase(Locale.US)
        val trimmed = password.trim()
        val isGmail = host == "imap.gmail.com" || host.endsWith(".gmail.com") || host.endsWith(".googlemail.com")
        if (isGmail && GMAIL_GROUPED_APP_PASSWORD.matches(trimmed)) {
            return trimmed.replace(" ", "")
        }
        return password
    }

    private suspend fun credential(store: EmailStore, account: EmailConfig): Credential? =
        when (account.auth) {
            EmailAuth.PASSWORD -> store.code(account.id)?.let {
                Credential.Password(normalizePassword(account.imapHost, it))
            }
            EmailAuth.MICROSOFT ->
                MicrosoftOAuth.accessToken(store, account.id, account.clientId)?.let { Credential.Token(it) }
        }

    private fun missingCredential(account: EmailConfig): String =
        if (account.auth == EmailAuth.MICROSOFT) {
            "Sign in with Microsoft first."
        } else {
            "The authorization code is missing."
        }

    private fun authenticateImap(imap: ImapClient, address: String, credential: Credential) {
        when (credential) {
            is Credential.Password -> imap.login(address, credential.value)
            is Credential.Token -> imap.loginXoauth2(address, credential.value)
        }
    }

    private fun authenticateSmtp(smtp: SmtpClient, address: String, credential: Credential) {
        when (credential) {
            is Credential.Password -> smtp.authenticate(address, credential.value)
            is Credential.Token -> smtp.authenticateXoauth2(address, credential.value)
        }
    }

    private fun openFolder(imap: ImapClient, requested: String): String {
        val target = requested.trim().ifEmpty { "INBOX" }
        try {
            imap.examine(target)
            return target
        } catch (firstError: EmailException) {
            val folders = runCatching { imap.listFolders() }.getOrDefault(emptyList())
            val matched = FolderMatcher.resolve(target, folders)
            if (matched != null) {
                imap.examine(matched.rawName)
                return matched.name
            }
            if (folders.isNotEmpty()) {
                val names = folders.take(20).joinToString(", ") { it.name }
                throw EmailException("IMAP: cannot open folder \"$target\". Available folders: $names")
            }
            throw firstError
        }
    }

    fun configured(context: Context): Boolean = EmailStore.configured(context)

    /** The global connector and this conversation's switch. */
    fun configuredFor(context: Context, sessionId: String): Boolean =
        EmailStore.configuredForSession(context, sessionId)

    suspend fun test(store: EmailStore, accountId: String): Outcome = withContext(Dispatchers.IO) {
        val account = store.account(accountId)
            ?: return@withContext Outcome(false, "The mailbox was not found.")
        val config = account.normalized()
        if (!config.complete) return@withContext Outcome(false, NOT_CONFIGURED)
        val credential = credential(store, account)
            ?: return@withContext Outcome(false, missingCredential(account))
        try {
            imapFactory(config).use { imap ->
                imap.open()
                authenticateImap(imap, config.address, credential)
                imap.examine("INBOX")
                imap.logout()
            }
            smtpFactory(config).use { smtp ->
                smtp.open()
                authenticateSmtp(smtp, config.address, credential)
                smtp.quit()
            }
            Outcome(true, "")
        } catch (error: Exception) {
            Outcome(false, describe(error))
        }
    }

    suspend fun read(
        store: EmailStore,
        accountId: String,
        folder: String,
        limit: Int,
        unreadOnly: Boolean,
        search: String?,
        uid: Int? = null,
        listFolders: Boolean = false,
    ): Outcome = withContext(Dispatchers.IO) {
        val account = store.account(accountId)
            ?: return@withContext Outcome(false, "The mailbox was not found.")
        val config = account.normalized()
        if (!config.enabled || !config.complete) return@withContext Outcome(false, NOT_CONFIGURED)
        val credential = credential(store, account)
            ?: return@withContext Outcome(false, missingCredential(account))
        try {
            imapFactory(config).use { imap ->
                imap.open()
                authenticateImap(imap, config.address, credential)
                if (listFolders) {
                    val folders = imap.listFolders()
                    imap.logout()
                    if (folders.isEmpty()) return@withContext Outcome(true, "Folders:\n- INBOX")
                    val lines = folders.joinToString("\n") { item ->
                        val roleTag = item.role
                            ?.takeIf { !item.name.equals("INBOX", ignoreCase = true) }
                            ?.let { " (${it.flag})" }
                            .orEmpty()
                        "- ${item.name}$roleTag"
                    }
                    return@withContext Outcome(true, "Folders:\n$lines")
                }

                openFolder(imap, folder)

                if (uid != null) {
                    if (uid <= 0) {
                        imap.logout()
                        return@withContext Outcome(false, "`uid` must be a positive message number.")
                    }
                    val size = imap.size(uid) ?: 0L
                    if (size > MAX_MESSAGE_BYTES) {
                        imap.logout()
                        return@withContext Outcome(false, "Message #$uid is larger than 25 MB.")
                    }
                    val mail = Mime.parse(imap.fetch(uid))
                    imap.logout()
                    return@withContext Outcome(true, renderSingleMessage(uid, mail))
                }

                val cappedLimit = limit.coerceIn(1, 30)
                val cleanSearch = search?.trim()?.ifEmpty { null }
                val serverUids = imap.searchOrNull(unreadOnly, cleanSearch)
                val preParsed = HashMap<Int, ParsedMail>()
                val chosen: List<Int> = when {
                    cleanSearch == null -> {
                        val allUids = serverUids ?: throw EmailException("IMAP: search failed")
                        allUids.takeLast(cappedLimit).reversed()
                    }
                    !serverUids.isNullOrEmpty() -> serverUids.takeLast(cappedLimit).reversed()
                    else -> {
                        // Fallback when the server rejects CHARSET UTF-8 or does not match
                        // encoded MIME headers/bodies in a server-side TEXT search.
                        val recent = imap.searchOrNull(unreadOnly, null)
                            .orEmpty()
                            .takeLast(CLIENT_SCAN_WINDOW)
                            .reversed()
                        val matched = mutableListOf<Int>()
                        for (candidateUid in recent) {
                            if (matched.size >= cappedLimit) break
                            val size = imap.size(candidateUid) ?: 0L
                            if (size > MAX_MESSAGE_BYTES) continue
                            val parsed = runCatching { Mime.parse(imap.fetch(candidateUid)) }.getOrNull()
                                ?: continue
                            if (matchesSearch(parsed, cleanSearch)) {
                                preParsed[candidateUid] = parsed
                                matched += candidateUid
                            }
                        }
                        matched
                    }
                }

                if (chosen.isEmpty()) {
                    imap.logout()
                    return@withContext Outcome(true, "No messages matched.")
                }
                val rendered = mutableListOf<String>()
                var skipped = 0
                var total = 0
                for (messageUid in chosen) {
                    val mail = preParsed[messageUid] ?: run {
                        val size = imap.size(messageUid) ?: 0L
                        if (size > MAX_MESSAGE_BYTES) {
                            skipped++
                            null
                        } else {
                            try {
                                Mime.parse(imap.fetch(messageUid))
                            } catch (_: Exception) {
                                skipped++
                                null
                            }
                        }
                    } ?: continue
                    val block = renderSummaryMessage(messageUid, mail)
                    if (total + block.length > TOTAL_CHARS) {
                        skipped++
                        continue
                    }
                    rendered += block
                    total += block.length
                }
                imap.logout()
                if (rendered.isEmpty()) return@withContext Outcome(true, "No messages matched.")
                val note = if (skipped > 0) {
                    "\n\n$skipped message(s) could not be read, were larger than 25 MB, or were past the output limit."
                } else {
                    ""
                }
                Outcome(true, rendered.joinToString("\n\n") + note)
            }
        } catch (error: Exception) {
            Outcome(false, describe(error))
        }
    }

    suspend fun resolveReply(
        store: EmailStore,
        accountId: String,
        replyToUid: Int,
        replyFolder: String,
        draftSubject: String,
    ): Result<ReplyContext> = withContext(Dispatchers.IO) {
        if (replyToUid <= 0) {
            return@withContext Result.failure(EmailException("`reply_to_uid` must be a positive message number."))
        }
        val account = store.account(accountId)
            ?: return@withContext Result.failure(EmailException("The mailbox was not found."))
        val config = account.normalized()
        if (!config.enabled || !config.complete) {
            return@withContext Result.failure(EmailException(NOT_CONFIGURED))
        }
        val credential = credential(store, account)
            ?: return@withContext Result.failure(EmailException(missingCredential(account)))
        try {
            imapFactory(config).use { imap ->
                imap.open()
                authenticateImap(imap, config.address, credential)
                openFolder(imap, replyFolder)
                val size = imap.size(replyToUid) ?: 0L
                if (size > MAX_MESSAGE_BYTES) {
                    imap.logout()
                    return@withContext Result.failure(EmailException("Message #$replyToUid is larger than 25 MB."))
                }
                val original = Mime.parse(imap.fetch(replyToUid))
                imap.logout()
                val inReplyTo = original.messageId.takeIf { it.isNotBlank() }
                val references = (original.references + listOfNotNull(inReplyTo)).distinct()
                val subject = draftSubject.trim().ifEmpty {
                    val base = original.subject.trim()
                    when {
                        base.isEmpty() -> "Re:"
                        base.startsWith("Re:", ignoreCase = true) -> base
                        else -> "Re: $base"
                    }
                }
                Result.success(ReplyContext(subject = subject, inReplyTo = inReplyTo, references = references))
            }
        } catch (error: Exception) {
            Result.failure(EmailException(describe(error)))
        }
    }

    suspend fun send(
        store: EmailStore,
        accountId: String,
        to: String,
        subject: String,
        body: String,
        cc: String?,
        bcc: String? = null,
        inReplyTo: String? = null,
        references: List<String> = emptyList(),
    ): Outcome = withContext(Dispatchers.IO) {
        val account = store.account(accountId)
            ?: return@withContext Outcome(false, "The mailbox was not found.")
        val config = account.normalized()
        if (!config.enabled || !config.complete) return@withContext Outcome(false, NOT_CONFIGURED)
        if (
            hasLineBreak(to) ||
            hasLineBreak(subject) ||
            (cc != null && hasLineBreak(cc)) ||
            (bcc != null && hasLineBreak(bcc)) ||
            (inReplyTo != null && hasLineBreak(inReplyTo))
        ) {
            return@withContext Outcome(false, "A recipient and the subject cannot contain a line break.")
        }
        val recipients = Mime.parseAddressList(to)
        if (recipients.isEmpty()) return@withContext Outcome(false, "`to` is required")
        val credential = credential(store, account)
            ?: return@withContext Outcome(false, missingCredential(account))
        val ccList = cc?.let { Mime.parseAddressList(it) } ?: emptyList()
        val bccList = bcc?.let { Mime.parseAddressList(it) } ?: emptyList()
        val envelopeRecipients = (recipients + ccList + bccList).map { it.address }.distinct()
        try {
            val data = Mime.build(
                from = config.address,
                to = recipients,
                cc = ccList,
                subject = subject,
                body = body,
                inReplyTo = inReplyTo,
                references = references,
            )
            smtpFactory(config).use { smtp ->
                smtp.open()
                authenticateSmtp(smtp, config.address, credential)
                smtp.sendMessage(config.address, envelopeRecipients, data)
                smtp.quit()
            }
            val ccPart = cc?.takeIf { it.isNotBlank() }?.let { " (cc $it)" }.orEmpty()
            val bccPart = bcc?.takeIf { it.isNotBlank() }?.let { " (bcc $it)" }.orEmpty()
            Outcome(true, "Email sent to $to$ccPart$bccPart: $subject")
        } catch (error: Exception) {
            Outcome(false, describe(error))
        }
    }

    private fun renderSummaryMessage(uid: Int, mail: ParsedMail): String {
        var body = EmailScrub.scrub(mail.body)
        if (body.length > BODY_CHARS) {
            body = body.take(BODY_CHARS) + "... [truncated, pass uid=$uid to read in full]"
        }
        return buildHeaderBlock(uid, mail, includeReplyTo = false) + "\n\n" + body
    }

    private fun renderSingleMessage(uid: Int, mail: ParsedMail): String {
        var body = EmailScrub.scrub(mail.body)
        val cap = (TOTAL_CHARS - 1_000).coerceAtLeast(BODY_CHARS)
        if (body.length > cap) {
            body = body.take(cap) + "... [truncated]"
        }
        return buildHeaderBlock(uid, mail, includeReplyTo = true) + "\n\n" + body
    }

    private fun buildHeaderBlock(uid: Int, mail: ParsedMail, includeReplyTo: Boolean): String = buildString {
        append("--- #").append(uid).append(" ---")
        append("\nFrom: ").append(mail.from)
        if (includeReplyTo && mail.replyTo.isNotBlank() && mail.replyTo != mail.from) {
            append("\nReply-To: ").append(mail.replyTo)
        }
        append("\nTo: ").append(mail.to)
        if (mail.cc.isNotBlank()) {
            append("\nCc: ").append(mail.cc)
        }
        append("\nDate: ").append(mail.date)
        append("\nSubject: ").append(mail.subject)
        if (mail.attachments.isNotEmpty()) {
            append("\nAttachments: ").append(mail.attachments.joinToString(", ") { it.summary() })
        }
    }

    private fun matchesSearch(mail: ParsedMail, term: String): Boolean {
        val needle = term.trim()
        if (needle.isEmpty()) return true
        return mail.subject.contains(needle, ignoreCase = true) ||
            mail.from.contains(needle, ignoreCase = true) ||
            mail.to.contains(needle, ignoreCase = true) ||
            mail.cc.contains(needle, ignoreCase = true) ||
            mail.body.contains(needle, ignoreCase = true) ||
            mail.attachments.any { it.filename.contains(needle, ignoreCase = true) }
    }

    private fun hasLineBreak(value: String): Boolean =
        value.any { it == '\r' || it == '\n' }

    private fun describe(error: Exception): String {
        val message = when (error) {
            is UnknownHostException -> "The mail server name was not found."
            is SocketTimeoutException -> "The mail server did not answer in time."
            is SSLException -> "TLS with the mail server failed (${error.message ?: "handshake error"})."
            is EmailException -> error.message ?: "Mail server error."
            else -> "Mail error: ${error.message ?: error.javaClass.simpleName}"
        }
        return message.take(300)
    }
}
