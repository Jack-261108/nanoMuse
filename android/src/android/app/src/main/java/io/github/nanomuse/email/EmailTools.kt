package io.github.nanomuse.email

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import io.github.nanomuse.guard.GateOutcome
import io.github.nanomuse.guard.GuardKind
import io.github.nanomuse.guard.RiskAssessment
import io.github.nanomuse.guard.RiskClass
import io.github.nanomuse.guard.RiskGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * `read_emails` and `send_email`, aligned with the runtime's contract. Sending is an OUTBOUND
 * action: the approval card names every recipient (To, Cc and Bcc) and shows the mail's text,
 * and a standing grant is bound to that exact recipient set.
 */
object EmailTools {
    const val READ = "read_emails"
    const val SEND = "send_email"

    private const val TOOL_TITLE =
        "A concise 5-10 word summary of what this tool call does, shown to the user " +
            "(e.g. 'Read unread mail', 'Send the weekly report'). Use the same language as the user."

    private const val OFF_FOR_SESSION = "The mailbox is turned off for this conversation."
    private const val NOT_CONFIGURED =
        "The email connector is not set up. Open Settings, Connectors, Email and connect a mailbox."

    fun definitions(): List<AgentToolDefinition> = listOf(readDefinition(), sendDefinition())

    fun promptParagraph(context: Context, sessionId: String): String? {
        if (!EmailConnector.configuredFor(context, sessionId)) return null
        val accounts = EmailStore.forContext(context).accounts()
            .filter { it.enabled && it.verified && it.complete }
        if (accounts.isEmpty()) return null
        return buildString {
            appendLine("Mailbox tools:")
            appendLine("- read_emails: read mail over IMAP (folder, limit, unread_only, search, uid for full message, list_folders).")
            appendLine("- send_email: send mail over SMTP (to, cc, bcc, subject, body, reply_to_uid). Show the draft to the user first unless they asked you to send.")
            if (accounts.size > 1) {
                appendLine(
                    "Several mailboxes are connected: " +
                        accounts.joinToString(", ") { it.displayLabel } +
                        ". Pass account to choose; send_email requires it.",
                )
            }
        }.trimEnd()
    }

    fun clampLimit(value: Int): Int = value.coerceIn(1, 30)

    fun textOf(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length()).joinToString(", ") { value.optString(it, "") }
        else -> value.toString()
    }

    fun parseRecipients(to: Any?, cc: Any?, bcc: Any? = null): List<Mailbox> =
        Mime.parseAddressList(textOf(to)) +
            Mime.parseAddressList(textOf(cc)) +
            Mime.parseAddressList(textOf(bcc))

    /** A mailbox by id, name or address; the model may use any of them. */
    fun findAccount(accounts: List<EmailConfig>, query: String): EmailConfig? {
        val wanted = query.trim()
        if (wanted.isEmpty()) return null
        return accounts.firstOrNull {
            it.id.equals(wanted, ignoreCase = true) ||
                it.label.equals(wanted, ignoreCase = true) ||
                it.address.equals(wanted, ignoreCase = true)
        }
    }

    fun assessSend(args: JSONObject, account: EmailConfig): RiskAssessment {
        val recipients = parseRecipients(args.opt("to"), args.opt("cc"), args.opt("bcc"))
            .map { it.address.lowercase(Locale.US) }
            .distinct()
            .sorted()
        return RiskAssessment(
            riskClass = RiskClass.OUTBOUND,
            reason = "sends an email from ${account.displayLabel}",
            target = recipients.joinToString(",").ifBlank { null },
        )
    }

    fun previewFor(
        args: JSONObject,
        account: EmailConfig,
        subjectOverride: String? = null,
    ): String {
        val body = textOf(args.opt("body"))
        val excerpt = if (body.length > 1_200) body.take(1_200) + "\n... [truncated]" else body
        val cc = textOf(args.opt("cc")).trim()
        val bcc = textOf(args.opt("bcc")).trim()
        val replyUid = positiveIntOrNull(args, "reply_to_uid")
        val subject = subjectOverride ?: textOf(args.opt("subject"))
        return buildString {
            append("From: ").append(account.displayLabel).append(" <").append(account.address).append(">\n")
            append("To: ").append(textOf(args.opt("to")).trim()).append("\n")
            if (cc.isNotEmpty()) append("Cc: ").append(cc).append("\n")
            if (bcc.isNotEmpty()) append("Bcc: ").append(bcc).append("\n")
            if (replyUid != null) append("Replying to: #").append(replyUid).append("\n")
            append("Subject: ").append(subject).append("\n\n")
            append(excerpt)
        }
    }

    suspend fun read(context: Context, sessionId: String, argsJson: String): ToolExecutionResult {
        val args = parse(argsJson) ?: return ToolExecutionResult("Invalid arguments.", false, toolTitle = READ)
        val toolTitle = args.optString("tool_title", READ)
        if (!EmailStore.configuredForSession(context, sessionId)) {
            return ToolExecutionResult(OFF_FOR_SESSION, false, toolTitle = toolTitle)
        }
        val store = EmailStore.forContext(context)
        val accounts = store.accounts().filter { it.enabled && it.verified && it.complete }
        if (accounts.isEmpty()) return ToolExecutionResult(NOT_CONFIGURED, false, toolTitle = toolTitle)
        val query = args.optString("account", "").trim()
        val account = if (query.isEmpty()) accounts.first() else findAccount(accounts, query)
        if (account == null) {
            return ToolExecutionResult(
                "No mailbox matches \"$query\". Connected: ${labels(accounts)}.",
                false,
                toolTitle = toolTitle,
            )
        }
        val uid = if (args.has("uid") && !args.isNull("uid")) args.optInt("uid", -1) else null
        val result = EmailConnector.read(
            store = store,
            accountId = account.id,
            folder = args.optString("folder", "INBOX"),
            limit = clampLimit(args.optInt("limit", 10)),
            unreadOnly = args.optBoolean("unread_only", false),
            search = args.optString("search", "").trim().ifBlank { null },
            uid = uid,
            listFolders = args.optBoolean("list_folders", false),
        )
        val prefix = if (accounts.size > 1) "Mailbox: ${account.displayLabel}\n\n" else ""
        return ToolExecutionResult(prefix + result.message, result.ok, toolTitle = toolTitle)
    }

    suspend fun send(context: Context, sessionId: String, argsJson: String): ToolExecutionResult {
        val args = parse(argsJson) ?: return ToolExecutionResult("Invalid arguments.", false, toolTitle = SEND)
        val toolTitle = args.optString("tool_title", SEND)
        if (!EmailStore.configuredForSession(context, sessionId)) {
            return ToolExecutionResult(OFF_FOR_SESSION, false, toolTitle = toolTitle)
        }
        val store = EmailStore.forContext(context)
        val accounts = store.accounts().filter { it.enabled && it.verified && it.complete }
        if (accounts.isEmpty()) return ToolExecutionResult(NOT_CONFIGURED, false, toolTitle = toolTitle)
        val query = args.optString("account", "").trim()
        if (query.isEmpty() && accounts.size > 1) {
            return ToolExecutionResult(
                "There are several mailboxes: ${labels(accounts)}. Say which one with the account parameter.",
                false,
                toolTitle = toolTitle,
            )
        }
        val account = if (query.isEmpty()) accounts.first() else findAccount(accounts, query)
        if (account == null) {
            return ToolExecutionResult(
                "No mailbox matches \"$query\". Connected: ${labels(accounts)}.",
                false,
                toolTitle = toolTitle,
            )
        }
        val to = textOf(args.opt("to")).trim()
        val cc = textOf(args.opt("cc")).trim().ifBlank { null }
        val bcc = textOf(args.opt("bcc")).trim().ifBlank { null }
        val rawSubject = textOf(args.opt("subject"))
        val body = textOf(args.opt("body"))
        if (to.isEmpty()) return ToolExecutionResult("`to` is required", false, toolTitle = toolTitle)
        if (
            hasLineBreak(to) ||
            hasLineBreak(rawSubject) ||
            (cc != null && hasLineBreak(cc)) ||
            (bcc != null && hasLineBreak(bcc))
        ) {
            return ToolExecutionResult(
                "A recipient and the subject cannot contain a line break.",
                false,
                toolTitle = toolTitle,
            )
        }
        val replyToUid = positiveIntOrNull(args, "reply_to_uid")
        val replyFolder = args.optString("reply_folder", "INBOX")
        val replyContext = if (replyToUid != null) {
            EmailConnector.resolveReply(store, account.id, replyToUid, replyFolder, rawSubject)
                .getOrElse { error ->
                    return ToolExecutionResult(
                        error.message ?: "Could not load message #$replyToUid to reply to.",
                        false,
                        toolTitle = toolTitle,
                    )
                }
        } else {
            null
        }
        val subject = replyContext?.subject ?: rawSubject
        when (
            val gate = RiskGate.check(
                sessionId,
                GuardKind.EMAIL,
                assessSend(args, account),
                previewFor(args, account, subjectOverride = subject),
            )
        ) {
            is GateOutcome.Denied -> return ToolExecutionResult(gate.message, false, toolTitle = toolTitle)
            is GateOutcome.Allowed -> Unit
        }
        val result = EmailConnector.send(
            store = store,
            accountId = account.id,
            to = to,
            subject = subject,
            body = body,
            cc = cc,
            bcc = bcc,
            inReplyTo = replyContext?.inReplyTo,
            references = replyContext?.references.orEmpty(),
        )
        return ToolExecutionResult(result.message, result.ok, toolTitle = toolTitle)
    }

    private fun positiveIntOrNull(args: JSONObject, key: String): Int? {
        if (!args.has(key) || args.isNull(key)) return null
        val value = args.optInt(key, -1)
        return value.takeIf { it > 0 }
    }

    private fun labels(accounts: List<EmailConfig>): String =
        accounts.joinToString(", ") { it.displayLabel }

    private fun parse(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()

    private fun hasLineBreak(value: String): Boolean =
        value.any { it == '\r' || it == '\n' }

    private fun readDefinition() = AgentToolDefinition(
        name = READ,
        description = "Read the user's mailbox over IMAP. Returns the most recent messages " +
            "(UID, sender, recipients, date, subject, attachments and text body). Pass uid to read " +
            "one message in full without the 3,000-character preview limit, or list_folders=true " +
            "to list the mailbox's folders. Common folder names such as Sent, Drafts, Trash, Junk " +
            "and Archive are matched automatically across providers. When several mailboxes are " +
            "connected, account picks one by name or address; without it the first one is used.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", TOOL_TITLE),
            "account" to AgentToolParam("string", "Mailbox name or address, when more than one is connected."),
            "folder" to AgentToolParam("string", "Mailbox folder, default INBOX (e.g. INBOX, Sent, Drafts, Trash, Junk)."),
            "limit" to AgentToolParam("integer", "How many messages, 1 to 30. Default 10."),
            "unread_only" to AgentToolParam("boolean", "Only unread messages."),
            "search" to AgentToolParam("string", "Free-text filter across subject, sender and body."),
            "uid" to AgentToolParam("integer", "Optional message UID (from #UID in an earlier result) to read one message in full."),
            "list_folders" to AgentToolParam("boolean", "When true, list the mailbox's folders instead of reading messages."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "account", "folder", "limit", "unread_only", "search", "uid", "list_folders"),
    )

    private fun sendDefinition() = AgentToolDefinition(
        name = SEND,
        description = "Send an email from the user's account over SMTP. Always show the user the " +
            "draft (to, subject, body) before sending unless they explicitly asked you to send " +
            "without review. Pass reply_to_uid to thread the message as a reply to an earlier " +
            "email. When several mailboxes are connected, account is required and picks the " +
            "sender by name or address.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", TOOL_TITLE),
            "account" to AgentToolParam("string", "Mailbox name or address to send from."),
            "to" to AgentToolParam("string", "Recipients, comma-separated; a display name before <address> is kept."),
            "cc" to AgentToolParam("string", "Optional Cc recipients, comma-separated."),
            "bcc" to AgentToolParam("string", "Optional Bcc recipients, comma-separated."),
            "subject" to AgentToolParam("string", "Subject line."),
            "body" to AgentToolParam("string", "Plain-text body."),
            "reply_to_uid" to AgentToolParam("integer", "Optional UID of the message being replied to, for In-Reply-To and References threading."),
            "reply_folder" to AgentToolParam("string", "Folder holding reply_to_uid, default INBOX."),
        ),
        required = listOf("tool_title", "to", "subject", "body"),
        propertyOrdering = listOf("tool_title", "account", "to", "cc", "bcc", "subject", "body", "reply_to_uid", "reply_folder"),
    )
}
