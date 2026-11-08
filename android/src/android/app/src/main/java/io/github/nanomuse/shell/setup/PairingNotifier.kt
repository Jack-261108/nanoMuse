package io.github.nanomuse.shell.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.R

/** 配对通知此刻该显示什么。 */
sealed interface PairingNotice {
    data class EnterCode(val port: Int?, val error: String? = null) : PairingNotice
    data object Working : PairingNotice
    data object Done : PairingNotice
    data class Problem(val title: String) : PairingNotice

    companion object {
        fun of(status: BuiltinShellStatus, discovery: PairingDiscovery): PairingNotice? {
            val port = (discovery as? PairingDiscovery.Found)?.port
            return when (status) {
                BuiltinShellStatus.Running -> Done
                BuiltinShellStatus.Connecting -> Working
                is BuiltinShellStatus.Failed -> when {
                    status.reason.duringPairing -> EnterCode(port, "Pairing failed. Please enter the 6-digit code again.")
                    status.fix == BuiltinShellFix.RePair && port != null -> EnterCode(port)
                    else -> Problem("Wireless Debugging Disconnected")
                }
                BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> port?.let { EnterCode(it) }
            }
        }

        const val AUTO_READ_GRACE_MS = 5_000L
        val REALERT_MS = listOf(8_000L, 20_000L, 40_000L)

        fun delayBefore(notice: PairingNotice, autoRead: Boolean): Long =
            if (notice is EnterCode && autoRead) AUTO_READ_GRACE_MS else 0L

        fun realertAfter(notice: PairingNotice, sent: Int): Long? =
            if (notice is EnterCode) REALERT_MS.getOrNull(sent) else null

        fun follow(status: BuiltinShellStatus, discovery: PairingDiscovery, repairing: Boolean, submitted: Boolean): Pair<PairingNotice?, Boolean> =
            if (submitted) Working to false
            else of(PairingScreen.effectiveStatus(status, repairing), discovery) to (status == BuiltinShellStatus.Running && !repairing)
    }
}

object PairingNotifier {
    const val KEY_CODE = "pairing_code"
    const val EXTRA_PORT = "pairing_port"
    const val EXTRA_SESSION = "pairing_session"
    private const val ID = 4402
    private const val CHANNEL_ID = "nanomuse_adb_pairing"
    private const val CHANNEL_NAME = "Wireless Debugging Pairing"
    private const val DONE_TIMEOUT_MS = 8_000L
    private const val INPUT_TIMEOUT_MS = 10 * 60_000L

    fun ensureChannel(context: Context): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setShowBadge(false)
            description = "Pairing code input for wireless debugging"
        }
        nm.createNotificationChannel(channel)
        return CHANNEL_ID
    }

    fun show(context: Context, notice: PairingNotice, sessionId: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = ensureChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val open = if (launchIntent != null) {
            PendingIntent.getActivity(
                context, 0, launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else null

        val b = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_nanomuse)
            .setCategory(Notification.CATEGORY_STATUS)
        if (open != null) b.setContentIntent(open)

        when (notice) {
            is PairingNotice.EnterCode -> {
                val input = RemoteInput.Builder(KEY_CODE)
                    .setLabel("6-digit pairing code")
                    .setAllowFreeFormInput(true)
                    .build()
                val reply = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, PairingCodeReceiver::class.java).apply {
                        notice.port?.let { putExtra(EXTRA_PORT, it) }
                        putExtra(EXTRA_SESSION, sessionId)
                        data = android.net.Uri.parse("nanomuse-pairing://session/$sessionId")
                    },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val action = Notification.Action.Builder(null, "Pair", reply)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
                val text = notice.error ?: "Enter the 6-digit wireless pairing code shown on screen."
                b.setContentTitle("Pair Wireless Debugging")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    .setOnlyAlertOnce(false)
                    .setTimeoutAfter(INPUT_TIMEOUT_MS)
                    .addAction(action.build())
                if (notice.port != null) b.setSubText("Pairing port: ${notice.port}")
            }
            PairingNotice.Working -> b.setContentTitle("Pairing Wireless Debugging...")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
            PairingNotice.Done -> b.setContentTitle("Paired Successfully")
                .setContentText("Privileged helper is now running in background.")
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(DONE_TIMEOUT_MS)
            is PairingNotice.Problem -> b.setContentTitle(notice.title)
                .setContentText("Please reopen nanoMuse to repair wireless connection.")
                .setAutoCancel(true)
        }
        runCatching { nm.notify(ID, b.build()) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
