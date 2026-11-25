package io.github.nanomuse.hands.pairing

import io.github.nanomuse.shell.setup.BuiltinShellFailure
import io.github.nanomuse.shell.setup.BuiltinShellStatus
import io.github.nanomuse.shell.setup.PairingDiscovery as ShellPairingDiscovery
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessStatusMapperTest {

    @Test
    fun connectingAndLiveDisplayTakePrecedence() {
        assertEquals(
            WirelessStatus.Connecting,
            WirelessStatusMapper.mapStatus(
                builtin = BuiltinShellStatus.Connecting,
                displayReady = false,
                paired = false,
                wirelessDebuggingOn = true,
            ),
        )
        assertEquals(
            WirelessStatus.Running,
            WirelessStatusMapper.mapStatus(
                builtin = BuiltinShellStatus.NotPaired,
                displayReady = true,
                paired = false,
                wirelessDebuggingOn = false,
            ),
        )
    }

    @Test
    fun detachedRunningHelperReflectsWirelessToggleAndRootFallbackAccurately() {
        // Wireless helper running, but user turned wireless debugging off -> display suspended.
        assertEquals(
            WirelessStatus.WirelessDebuggingOff,
            WirelessStatusMapper.mapStatus(
                builtin = BuiltinShellStatus.Running,
                displayReady = false,
                paired = true,
                wirelessDebuggingOn = false,
            ),
        )
        // Root helper running (no IHandsDisplay) while never paired wirelessly -> NotPaired.
        assertEquals(
            WirelessStatus.NotPaired,
            WirelessStatusMapper.mapStatus(
                builtin = BuiltinShellStatus.Running,
                displayReady = false,
                paired = false,
                wirelessDebuggingOn = true,
            ),
        )
        // Paired & wireless debugging on, helper running without IHandsDisplay -> HelperStartFailed.
        assertEquals(
            WirelessStatus.Failed(WirelessFailure.HelperStartFailed),
            WirelessStatusMapper.mapStatus(
                builtin = BuiltinShellStatus.Running,
                displayReady = false,
                paired = true,
                wirelessDebuggingOn = true,
            ),
        )
    }

    @Test
    fun stoppedPairedManagerMapsToWirelessDebuggingOffForAutoReconnect() {
        assertEquals(
            WirelessStatus.WirelessDebuggingOff,
            WirelessStatusMapper.mapStatus(
                builtin = BuiltinShellStatus.NotPaired,
                displayReady = false,
                paired = true,
                wirelessDebuggingOn = true,
            ),
        )
        assertEquals(
            WirelessStatus.NotPaired,
            WirelessStatusMapper.mapStatus(
                builtin = BuiltinShellStatus.NotPaired,
                displayReady = false,
                paired = false,
                wirelessDebuggingOn = true,
            ),
        )
    }

    @Test
    fun mapsPairingKeyAndConnectionFailuresConsistently() {
        assertEquals(
            WirelessStatus.Failed(WirelessFailure.WrongCode),
            WirelessStatusMapper.mapStatus(
                BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode),
                displayReady = false,
                paired = false,
                wirelessDebuggingOn = true,
            ),
        )
        assertEquals(
            WirelessStatus.Failed(WirelessFailure.PairingWindowClosed),
            WirelessStatusMapper.mapStatus(
                BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed),
                displayReady = false,
                paired = false,
                wirelessDebuggingOn = true,
            ),
        )
        assertEquals(
            WirelessStatus.Failed(WirelessFailure.AuthorizationRevoked),
            WirelessStatusMapper.mapStatus(
                BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked),
                displayReady = false,
                paired = false,
                wirelessDebuggingOn = true,
            ),
        )
        assertEquals(
            WirelessStatus.Failed(WirelessFailure.AuthorizationRevoked),
            WirelessStatusMapper.mapStatus(
                BuiltinShellStatus.Failed(BuiltinShellFailure.KeyUnreadable),
                displayReady = false,
                paired = false,
                wirelessDebuggingOn = true,
            ),
        )
        assertEquals(
            WirelessStatus.NotPaired,
            WirelessStatusMapper.mapStatus(
                BuiltinShellStatus.Failed(BuiltinShellFailure.BackendUnavailable),
                displayReady = false,
                paired = false,
                wirelessDebuggingOn = true,
            ),
        )
        assertEquals(
            WirelessStatus.WirelessDebuggingOff,
            WirelessStatusMapper.mapStatus(
                BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed),
                displayReady = false,
                paired = true,
                wirelessDebuggingOn = false,
            ),
        )
        assertEquals(
            WirelessStatus.Failed(WirelessFailure.NoWifi),
            WirelessStatusMapper.mapStatus(
                BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi),
                displayReady = false,
                paired = true,
                wirelessDebuggingOn = false,
            ),
        )
    }

    @Test
    fun mapsDiscoveryStatesDirectly() {
        assertEquals(PairingDiscovery.Idle, WirelessStatusMapper.mapDiscovery(ShellPairingDiscovery.Idle))
        assertEquals(PairingDiscovery.Searching, WirelessStatusMapper.mapDiscovery(ShellPairingDiscovery.Searching))
        assertEquals(PairingDiscovery.Found(41234), WirelessStatusMapper.mapDiscovery(ShellPairingDiscovery.Found(41234)))
    }
}
