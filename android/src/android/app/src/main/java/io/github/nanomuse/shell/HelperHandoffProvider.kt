package io.github.nanomuse.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.hands.IHandsDisplay

/**
 * 接收单一特权辅助进程交付的 INanoMuseShell / IHandsDisplay Binder 的 ContentProvider。
 */
class HelperHandoffProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == HelperHandoff.METHOD_ATTACH && extras != null) {
            val shellBinder = extras.getBinder(HelperHandoff.KEY_BINDER)
            val displayBinder = extras.getBinder(HelperHandoff.KEY_DISPLAY_BINDER)
            val pid = extras.getInt(HelperHandoff.KEY_PID, -1)
            val uid = extras.getInt(HelperHandoff.KEY_UID, -1)
            val wireless = extras.getBoolean(HelperHandoff.KEY_WIRELESS, false)
            val callerUid = Binder.getCallingUid()
            val callerPid = Binder.getCallingPid()
            if (shellBinder != null && HelperCallerAuth.acceptsHandoffCaller(
                    callingUid = callerUid,
                    callingPid = callerPid,
                    reportedUid = uid,
                    reportedPid = pid,
                    hasDisplayBinder = displayBinder != null,
                )
            ) {
                val shell = INanoMuseShell.Stub.asInterface(shellBinder)
                val display = displayBinder?.let(IHandsDisplay.Stub::asInterface)
                val caller = Binder.clearCallingIdentity()
                val accepted = try {
                    SharedShell.attach(
                        shell = shell,
                        display = display,
                        pid = pid,
                        uid = uid,
                        nonce = extras.getString(HelperHandoff.KEY_NONCE),
                        wireless = wireless,
                    )
                } finally {
                    Binder.restoreCallingIdentity(caller)
                }
                if (accepted) {
                    AppLogger.info(
                        TAG,
                        "shell helper attached successfully (pid=$pid, uid=$uid, display=${display != null}, wireless=$wireless)",
                    )
                }
                return Bundle().apply {
                    putBoolean(HelperHandoff.KEY_ACCEPTED, accepted)
                    if (accepted) putBinder(HelperHandoff.KEY_CLIENT_TOKEN, clientToken)
                }
            }
            return Bundle().apply { putBoolean(HelperHandoff.KEY_ACCEPTED, false) }
        }
        return super.call(method, arg, extras)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "HelperHandoffProvider"
        private val clientToken = Binder()
    }
}
