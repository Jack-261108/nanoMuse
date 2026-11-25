package io.github.nanomuse.hands

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.nanomuse.hands.pairing.HandsWireless
import io.github.nanomuse.shell.HelperHandoff
import io.github.nanomuse.shell.SharedShell
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A claim is valid once, and a disconnect invalidates even a handshake already in flight. */
internal class HandsBridgeTickets {
    private var generation = 0L
    private var ticket: String? = null
    private var expires = 0L

    fun issue(nonce: String, now: Long) {
        generation++
        ticket = nonce
        expires = now + 90_000L
    }

    fun clear() {
        generation++
        ticket = null
    }

    fun consume(nonce: String, now: Long): Long? {
        val expected = ticket ?: return null
        if (now >= expires || !MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII),
                nonce.toByteArray(Charsets.US_ASCII))) return null
        ticket = null
        return generation
    }

    fun current(id: Long) = generation == id
}

/**
 * Narrow IHandsDisplay projection backed by the single unified [SharedShell] helper process.
 * Death or release of the helper invalidates both INanoMuseShell and IHandsDisplay together.
 */
internal object HandsDisplayBridge {
    const val METHOD = "offerHandsDisplay"
    const val SHELL_UID = HelperHandoff.SHELL_UID
    private val state = MutableStateFlow(false)
    val ready = state.asStateFlow()

    fun isReady(): Boolean = current() != null

    fun current(): IHandsDisplay? = SharedShell.currentDisplay()

    fun disconnect() {
        SharedShell.release()
    }

    internal fun updateReady(isReady: Boolean) {
        state.value = isReady
        HandsWireless.syncFromBuiltin()
    }
}

/**
 * Legacy bridge provider kept inert so no alternate display service can be registered here;
 * all privileged handoffs go through [io.github.nanomuse.shell.HelperHandoffProvider].
 */
class HandsDisplayBridgeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
