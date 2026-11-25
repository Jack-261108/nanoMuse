package io.github.nanomuse.hands.pairing

import io.github.nanomuse.shell.setup.BuiltinShellFailure
import io.github.nanomuse.shell.setup.BuiltinShellStatus
import io.github.nanomuse.shell.setup.PairingDiscovery as ShellPairingDiscovery

/** Maps BuiltinShellManager's unified state into the view model expected by Hands wireless setup. */
internal object WirelessStatusMapper {
    fun mapStatus(
        builtin: BuiltinShellStatus,
        displayReady: Boolean,
        paired: Boolean,
        wirelessDebuggingOn: Boolean?,
    ): WirelessStatus {
        if (builtin is BuiltinShellStatus.Connecting) return WirelessStatus.Connecting
        if (displayReady) return WirelessStatus.Running
        return when (builtin) {
            BuiltinShellStatus.NotPaired ->
                if (paired) WirelessStatus.WirelessDebuggingOff else WirelessStatus.NotPaired
            BuiltinShellStatus.WirelessDebuggingOff ->
                if (paired) WirelessStatus.WirelessDebuggingOff else WirelessStatus.NotPaired
            BuiltinShellStatus.Running -> when {
                !paired -> WirelessStatus.NotPaired
                wirelessDebuggingOn == false -> WirelessStatus.WirelessDebuggingOff
                else -> WirelessStatus.Failed(WirelessFailure.HelperStartFailed)
            }
            is BuiltinShellStatus.Failed -> mapFailure(builtin.reason, paired)
            BuiltinShellStatus.Connecting -> WirelessStatus.Connecting
        }
    }

    private fun mapFailure(reason: BuiltinShellFailure, paired: Boolean): WirelessStatus = when (reason) {
        BuiltinShellFailure.WrongCode -> WirelessStatus.Failed(WirelessFailure.WrongCode)
        BuiltinShellFailure.PairingWindowClosed -> WirelessStatus.Failed(WirelessFailure.PairingWindowClosed)
        BuiltinShellFailure.AuthorizationRevoked,
        BuiltinShellFailure.KeyUnreadable,
        -> WirelessStatus.Failed(WirelessFailure.AuthorizationRevoked)
        BuiltinShellFailure.WifiNotAllowed ->
            if (paired) WirelessStatus.WirelessDebuggingOff else WirelessStatus.NotPaired
        BuiltinShellFailure.NoWifi ->
            if (paired) WirelessStatus.Failed(WirelessFailure.NoWifi) else WirelessStatus.NotPaired
        BuiltinShellFailure.ConnectFailed ->
            if (paired) WirelessStatus.Failed(WirelessFailure.ConnectFailed) else WirelessStatus.NotPaired
        BuiltinShellFailure.HelperStartFailed ->
            if (paired) WirelessStatus.Failed(WirelessFailure.HelperStartFailed) else WirelessStatus.NotPaired
        BuiltinShellFailure.BackendUnavailable,
        BuiltinShellFailure.RootDenied,
        BuiltinShellFailure.UsbHelperStopped,
        BuiltinShellFailure.UnsupportedAndroid,
        BuiltinShellFailure.CleanupPending,
        BuiltinShellFailure.Unknown,
        -> if (paired) WirelessStatus.Failed(WirelessFailure.Unknown) else WirelessStatus.NotPaired
    }

    fun mapDiscovery(discovery: ShellPairingDiscovery): PairingDiscovery = when (discovery) {
        ShellPairingDiscovery.Idle -> PairingDiscovery.Idle
        ShellPairingDiscovery.Searching -> PairingDiscovery.Searching
        is ShellPairingDiscovery.Found -> PairingDiscovery.Found(discovery.port)
    }
}
