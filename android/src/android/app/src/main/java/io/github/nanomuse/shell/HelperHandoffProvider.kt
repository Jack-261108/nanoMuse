package io.github.nanomuse.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import com.openminis.app.logging.AppLogger

/**
 * 接收特权辅助进程交付的 Binder 的 ContentProvider。
 */
class HelperHandoffProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == HelperHandoff.METHOD_ATTACH && extras != null) {
            val binder = extras.getBinder(HelperHandoff.KEY_BINDER)
            val pid = extras.getInt(HelperHandoff.KEY_PID, -1)
            val uid = extras.getInt(HelperHandoff.KEY_UID, -1)
            val caller = Binder.getCallingUid()
            if (binder != null && caller in setOf(0, 2000) && uid == caller && pid == Binder.getCallingPid()) {
                val shell = INanoMuseShell.Stub.asInterface(binder)
                val accepted = SharedShell.setShell(shell, pid, uid, extras.getString(HelperHandoff.KEY_NONCE))
                if (accepted) AppLogger.info(TAG, "shell helper attached successfully (pid=$pid, uid=$uid)")
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
