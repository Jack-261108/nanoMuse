package io.github.nanomuse.email

/**
 * The runtime's mail scrubber (`nanomuse/tools/email_tool.py`), character for character:
 * one-time codes and password-reset links are removed before the model ever reads a mail.
 */
object EmailScrub {
    private val otp = Regex(
        "(?i)(verification code|verify code|security code|one[- ]time (?:code|password)|otp|passcode|" +
            "confirmation code|auth(?:entication)? code|验证码|校验码|动态码|驗證碼)" +
            "(.{0,40}?)(?<![0-9A-Za-z])([0-9]{4,8}|[A-Z0-9]{6,8})(?![0-9A-Za-z])",
    )

    private val sensitiveLink = Regex(
        "(?i)https?://[^\\s<>\"']*(?:reset|password|passwd|token=|verify|verification|confirm|magic|" +
            "login|signin|auth|unsubscribe)[^\\s<>\"']*",
    )

    fun scrub(text: String): String {
        val withoutCodes = otp.replace(text) { match ->
            match.groupValues[1] + match.groupValues[2] + "[REDACTED-CODE]"
        }
        return sensitiveLink.replace(withoutCodes, "[REDACTED-LINK]")
    }
}
