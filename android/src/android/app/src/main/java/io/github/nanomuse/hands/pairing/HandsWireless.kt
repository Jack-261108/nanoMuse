// SPDX-License-Identifier: Apache-2.0
// Adapted from Mika (Copyright 2026 wangzan101) for nanoMuse local wireless pairing.
package io.github.nanomuse.hands.pairing

import android.content.Context
import io.github.nanomuse.hands.HandsDisplayBridge
import io.github.nanomuse.shell.SharedShell
import io.github.nanomuse.shell.setup.BuiltinShellManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Facade for Hands wireless background screen setup, delegating pairing, discovery, transport,
 * and lifecycle to [BuiltinShellManager] so there is one source of truth and one helper process.
 */
object HandsWireless {
    private const val LEGACY_PREFS = "nanomuse_hands_wireless"

    @Volatile private var boundManager: BuiltinShellManager? = null
    @Volatile private var boundApp: Context? = null

    private val _status = MutableStateFlow<WirelessStatus>(WirelessStatus.NotPaired)
    internal val status: StateFlow<WirelessStatus> = _status.asStateFlow()

    private val _pairingDiscovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    internal val pairingDiscovery: StateFlow<PairingDiscovery> = _pairingDiscovery.asStateFlow()

    private val _scanningPorts = MutableStateFlow(false)
    internal val scanningPorts: StateFlow<Boolean> = _scanningPorts.asStateFlow()

    internal fun bind(manager: BuiltinShellManager, appContext: Context) {
        boundManager = manager
        boundApp = appContext.applicationContext
        syncFromBuiltin()
    }

    private fun manager(context: Context): BuiltinShellManager {
        val app = context.applicationContext
        val m = BuiltinShellManager.instance(app)
        if (boundManager !== m) bind(m, app)
        return m
    }

    internal fun syncFromBuiltin() {
        val m = boundManager ?: return
        val app = boundApp
        val wirelessOn = app?.let { DeveloperSettings.wirelessDebugging(it.contentResolver) }
        _status.value = WirelessStatusMapper.mapStatus(
            builtin = m.status.value,
            displayReady = HandsDisplayBridge.isReady(),
            paired = m.paired,
            wirelessDebuggingOn = wirelessOn,
        )
        _pairingDiscovery.value = WirelessStatusMapper.mapDiscovery(m.pairingDiscovery.value)
        _scanningPorts.value = m.scanningPorts.value
    }

    internal fun loopback(port: Int): InetSocketAddress {
        require(port in 1..65535)
        return InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
    }

    fun hasIdentity(context: Context): Boolean = manager(context).paired

    /** Reconciles [status] with current bridge readiness, pairing state and wireless debugging toggle. */
    internal fun refreshState(context: Context) {
        manager(context).refreshWirelessState()
    }

    /** Starts or restarts background mDNS discovery for the system pairing dialog port. */
    internal fun startDiscovery(context: Context) {
        manager(context).startDiscovery()
    }

    internal fun stopDiscovery() {
        boundManager?.stopDiscovery()
        _pairingDiscovery.value = PairingDiscovery.Idle
    }

    suspend fun discover(context: Context, pairing: Boolean): List<Int> =
        manager(context).discoverPorts(pairing)

    suspend fun pair(context: Context, port: Int, code: String) {
        manager(context).pairOnly(port, code, grantRecoveryPermissions = false)
    }

    /**
     * Pairs using [code] and [port] (falling back to mDNS discovery when [port] is null),
     * then immediately connects and starts the unified helper without auto-applying
     * BuiltinShellManager's extra recovery permissions.
     */
    internal suspend fun startPairing(context: Context, code: String, port: Int? = null) {
        val m = manager(context)
        _status.value = WirelessStatus.Connecting
        m.startPairing(code, port, grantRecoveryPermissions = false, requireDisplay = true)
        syncFromBuiltin()
    }

    /** Connects using all port sources (or an explicit [manualPort]) and updates [status]. */
    internal suspend fun connectAndActivate(context: Context, manualPort: Int? = null) {
        val m = manager(context)
        m.connectForDisplay(manualPort = manualPort, throwOnFailure = false)
        syncFromBuiltin()
    }

    internal suspend fun retry(context: Context) {
        connectAndActivate(context, manualPort = null)
    }

    suspend fun activate(context: Context, port: Int) {
        val m = manager(context)
        m.connectForDisplay(manualPort = port, throwOnFailure = true)
        syncFromBuiltin()
    }

    /** Reconnect using cached port, system property, mDNS candidates, or loopback scan. */
    suspend fun reconnect(context: Context): Boolean {
        val m = manager(context)
        SharedShell.refreshDisplayReadiness()
        if (HandsDisplayBridge.isReady()) {
            syncFromBuiltin()
            return true
        }
        if (!m.paired) return false
        return withTimeoutOrNull(18_000L) {
            try {
                m.connectForDisplay(manualPort = null, throwOnFailure = false)
                HandsDisplayBridge.isReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        } ?: false
    }

    suspend fun forget(context: Context) {
        val app = context.applicationContext
        val m = manager(app)
        m.forget()
        // Best-effort cleanup of legacy pre-merge Hands wireless key/marker if present.
        runCatching { AdbIdentity.forget() }
        runCatching { File(app.noBackupFilesDir, "hands-wireless-paired").delete() }
        runCatching { app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
        syncFromBuiltin()
    }

    internal fun mapPairingFailure(reason: AdbPairingException.Reason): WirelessFailure = when (reason) {
        AdbPairingException.Reason.WRONG_CODE -> WirelessFailure.WrongCode
        AdbPairingException.Reason.CONNECT_FAILED -> WirelessFailure.PairingWindowClosed
        AdbPairingException.Reason.TLS_FAILED, AdbPairingException.Reason.PROTOCOL -> WirelessFailure.Unknown
    }

    internal fun mapConnectionFailure(reason: AdbConnectionException.Reason, wifiConnected: Boolean): WirelessFailure = when (reason) {
        AdbConnectionException.Reason.UNAUTHORIZED -> WirelessFailure.AuthorizationRevoked
        AdbConnectionException.Reason.HELPER_START_FAILED -> WirelessFailure.HelperStartFailed
        AdbConnectionException.Reason.CONNECT_FAILED, AdbConnectionException.Reason.TLS_FAILED ->
            if (!wifiConnected) WirelessFailure.NoWifi else WirelessFailure.ConnectFailed
    }
}
