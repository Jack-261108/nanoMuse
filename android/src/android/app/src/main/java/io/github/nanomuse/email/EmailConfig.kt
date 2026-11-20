package io.github.nanomuse.email

import java.util.Locale

enum class EmailAuth { PASSWORD, MICROSOFT }

enum class MailHelpKind { QQ, NETEASE, GMAIL, APP_PASSWORD, MICROSOFT }

/**
 * One mailbox the native Android agent can read and send from. The authorization code or
 * OAuth tokens are never part of this object: they live in the encrypted store
 * ([EmailStore]) and only [hasCode] / [hasToken] say whether one is there.
 */
data class EmailConfig(
    val id: String = "",
    val label: String = "",
    val enabled: Boolean = false,
    val verified: Boolean = false,
    val address: String = "",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpStarttls: Boolean = true,
    val auth: EmailAuth = EmailAuth.PASSWORD,
    val clientId: String = "",
    val hasCode: Boolean = false,
    val hasToken: Boolean = false,
) {
    val complete: Boolean
        get() = address.isNotBlank() && imapHost.isNotBlank() && smtpHost.isNotBlank() &&
            when (auth) {
                EmailAuth.PASSWORD -> hasCode
                EmailAuth.MICROSOFT -> hasToken
            }

    /** What the cards, the tool output and the approval preview call this mailbox. */
    val displayLabel: String
        get() = label.trim().ifBlank { address.trim().ifBlank { "Mailbox" } }

    /** The config the tools use: trimmed, with ports in range and the STARTTLS default sane. */
    fun normalized(): EmailConfig = copy(
        address = address.trim(),
        label = label.trim(),
        imapHost = imapHost.trim(),
        smtpHost = smtpHost.trim(),
        clientId = clientId.trim(),
        imapPort = imapPort.coerceIn(1, 65_535),
        smtpPort = smtpPort.coerceIn(1, 65_535),
    )

    fun issues(): List<EmailIssue> = buildList {
        val addr = address.trim()
        val at = addr.indexOf('@')
        if (addr.isEmpty() || at <= 0 || at == addr.lastIndex || !addr.substring(at + 1).contains('.')) {
            add(EmailIssue.ADDRESS)
        }
        if (imapHost.trim().isEmpty()) add(EmailIssue.IMAP_HOST)
        if (smtpHost.trim().isEmpty()) add(EmailIssue.SMTP_HOST)
        if (imapPort !in 1..65_535 || smtpPort !in 1..65_535) add(EmailIssue.PORT)
        when (auth) {
            EmailAuth.PASSWORD -> if (!hasCode) add(EmailIssue.CODE)
            EmailAuth.MICROSOFT -> if (!hasToken) add(EmailIssue.TOKEN)
        }
    }
}

enum class EmailIssue { ADDRESS, IMAP_HOST, SMTP_HOST, PORT, CODE, TOKEN }

data class MailPreset(
    val id: String,
    val label: String,
    val imapHost: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpStarttls: Boolean,
    val auth: EmailAuth = EmailAuth.PASSWORD,
    val imapPort: Int = 993,
    val domains: List<String> = emptyList(),
    val help: MailHelpKind = MailHelpKind.APP_PASSWORD,
)

/**
 * Common personal and enterprise mail providers, with domain matching so typing an address
 * fills in the servers when the person has not entered custom ones yet.
 */
object MailPresets {
    val all: List<MailPreset> = listOf(
        MailPreset(
            id = "qq",
            label = "QQ",
            imapHost = "imap.qq.com",
            smtpHost = "smtp.qq.com",
            smtpPort = 587,
            smtpStarttls = true,
            domains = listOf("qq.com", "foxmail.com", "vip.qq.com"),
            help = MailHelpKind.QQ,
        ),
        MailPreset(
            id = "exmail",
            label = "QQ Exmail",
            imapHost = "imap.exmail.qq.com",
            smtpHost = "smtp.exmail.qq.com",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("exmail.qq.com"),
            help = MailHelpKind.QQ,
        ),
        MailPreset(
            id = "163",
            label = "163",
            imapHost = "imap.163.com",
            smtpHost = "smtp.163.com",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("163.com", "vip.163.com"),
            help = MailHelpKind.NETEASE,
        ),
        MailPreset(
            id = "126",
            label = "126",
            imapHost = "imap.126.com",
            smtpHost = "smtp.126.com",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("126.com"),
            help = MailHelpKind.NETEASE,
        ),
        MailPreset(
            id = "aliyun",
            label = "Aliyun",
            imapHost = "imap.aliyun.com",
            smtpHost = "smtp.aliyun.com",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("aliyun.com"),
            help = MailHelpKind.APP_PASSWORD,
        ),
        MailPreset(
            id = "aliqiye",
            label = "Aliyun Work",
            imapHost = "imap.qiye.aliyun.com",
            smtpHost = "smtp.qiye.aliyun.com",
            smtpPort = 465,
            smtpStarttls = false,
            help = MailHelpKind.APP_PASSWORD,
        ),
        MailPreset(
            id = "feishu",
            label = "Feishu",
            imapHost = "imap.feishu.cn",
            smtpHost = "smtp.feishu.cn",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("feishu.cn"),
            help = MailHelpKind.APP_PASSWORD,
        ),
        MailPreset(
            id = "sina",
            label = "Sina",
            imapHost = "imap.sina.com",
            smtpHost = "smtp.sina.com",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("sina.com"),
            help = MailHelpKind.APP_PASSWORD,
        ),
        MailPreset(
            id = "gmail",
            label = "Gmail",
            imapHost = "imap.gmail.com",
            smtpHost = "smtp.gmail.com",
            smtpPort = 587,
            smtpStarttls = true,
            domains = listOf("gmail.com", "googlemail.com"),
            help = MailHelpKind.GMAIL,
        ),
        MailPreset(
            id = "outlook",
            label = "Outlook",
            imapHost = "outlook.office365.com",
            smtpHost = "smtp.office365.com",
            smtpPort = 587,
            smtpStarttls = true,
            auth = EmailAuth.MICROSOFT,
            domains = listOf("outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.cn"),
            help = MailHelpKind.MICROSOFT,
        ),
        MailPreset(
            id = "icloud",
            label = "iCloud",
            imapHost = "imap.mail.me.com",
            smtpHost = "smtp.mail.me.com",
            smtpPort = 587,
            smtpStarttls = true,
            domains = listOf("icloud.com", "me.com", "mac.com"),
            help = MailHelpKind.APP_PASSWORD,
        ),
        MailPreset(
            id = "yahoo",
            label = "Yahoo",
            imapHost = "imap.mail.yahoo.com",
            smtpHost = "smtp.mail.yahoo.com",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("yahoo.com", "ymail.com"),
            help = MailHelpKind.APP_PASSWORD,
        ),
        MailPreset(
            id = "fastmail",
            label = "Fastmail",
            imapHost = "imap.fastmail.com",
            smtpHost = "smtp.fastmail.com",
            smtpPort = 587,
            smtpStarttls = true,
            domains = listOf("fastmail.com", "fastmail.fm"),
            help = MailHelpKind.APP_PASSWORD,
        ),
    )

    private val extraDomainPresets: List<MailPreset> = listOf(
        MailPreset(
            id = "yeah",
            label = "Yeah.net",
            imapHost = "imap.yeah.net",
            smtpHost = "smtp.yeah.net",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("yeah.net"),
            help = MailHelpKind.NETEASE,
        ),
        MailPreset(
            id = "sina-cn",
            label = "Sina.cn",
            imapHost = "imap.sina.cn",
            smtpHost = "smtp.sina.cn",
            smtpPort = 465,
            smtpStarttls = false,
            domains = listOf("sina.cn"),
            help = MailHelpKind.APP_PASSWORD,
        ),
    )

    /** Matches an email address's domain to a known preset, or null for custom domains. */
    fun forAddress(address: String): MailPreset? {
        val trimmed = address.trim()
        val at = trimmed.lastIndexOf('@')
        if (at <= 0 || at == trimmed.lastIndex) return null
        val domain = trimmed.substring(at + 1).lowercase(Locale.US)
        return (all + extraDomainPresets).firstOrNull { preset ->
            preset.domains.any { it == domain }
        }
    }

    /** Picks the provider hint that matches the current auth mode and IMAP server. */
    fun helpFor(imapHost: String, auth: EmailAuth): MailHelpKind {
        if (auth == EmailAuth.MICROSOFT) return MailHelpKind.MICROSOFT
        val host = imapHost.trim().lowercase(Locale.US)
        if (host.isEmpty()) return MailHelpKind.QQ
        (all + extraDomainPresets).firstOrNull { it.imapHost.equals(host, ignoreCase = true) }?.let {
            return it.help
        }
        return when {
            host.endsWith(".qq.com") -> MailHelpKind.QQ
            host.endsWith(".163.com") || host.endsWith(".126.com") || host.endsWith(".yeah.net") ->
                MailHelpKind.NETEASE
            host.endsWith(".gmail.com") || host.endsWith(".googlemail.com") -> MailHelpKind.GMAIL
            else -> MailHelpKind.APP_PASSWORD
        }
    }
}
