package io.github.nanomuse.shell.setup

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收来自通知栏 RemoteInput 的配对码输入广播。
 */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, -1).takeIf { it > 0 }
        val sessionId = intent.getLongExtra(PairingNotifier.EXTRA_SESSION, -1L)
        BuiltinShellManager.instance(context).coordinator.submit(code, port, sessionId)
    }
}
