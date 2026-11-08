package io.github.nanomuse.shell

import io.github.nanomuse.shell.recovery.PermissionCommands
import io.github.nanomuse.shell.recovery.PermissionResult
import io.github.nanomuse.shell.recovery.RecoveryCapability
import io.github.nanomuse.shell.recovery.RecoveryConsent
import io.github.nanomuse.shell.recovery.RecoveryConsentStore
import io.github.nanomuse.shell.recovery.RecoveryPermissions
import io.github.nanomuse.shell.recovery.ShellCommandRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecoveryPermissionsTest {
    private class Store(initial: RecoveryConsent) : RecoveryConsentStore {
        override val consent = MutableStateFlow(initial)
        override fun save(consent: RecoveryConsent) { this.consent.value = consent }
    }

    @Test fun failedCleanupRetainsOnlyUnrevokedGrantsAndCanRetry() = runTest {
        val store = Store(RecoveryConsent(applied = RecoveryCapability.entries.toSet()))
        var failBattery = true
        val commands = mutableListOf<String>()
        val runner = ShellCommandRunner { command ->
            commands += command
            when {
                command == PermissionCommands.READ_ACCESSIBILITY -> "example.other/.Service:example.app/com.openminis.app.accessibility.MinisAccessibilityService"
                failBattery && "whitelist -" in command -> "${PermissionCommands.EXIT_MARK}1"
                else -> "${PermissionCommands.EXIT_MARK}0"
            }
        }
        val permissions = RecoveryPermissions({ runner }, store, "example.app")
        assertTrue(permissions.revokeAll() is PermissionResult.Failed)
        assertEquals(setOf(RecoveryCapability.BatteryWhitelist), store.consent.value.applied)
        assertEquals(1, commands.count { "pm revoke" in it })
        assertTrue(commands.any { "example.other/.Service" in it })
        failBattery = false
        commands.clear()
        assertEquals(PermissionResult.Done, permissions.revokeAll())
        assertTrue(store.consent.value.applied.isEmpty())
        assertEquals(1, commands.size)
    }

    @Test fun missingConnectionKeepsGrantsAndUserPreferences() = runTest {
        val granted = setOf(RecoveryCapability.Accessibility, RecoveryCapability.AutoWirelessDebugging)
        val declined = setOf(RecoveryCapability.BatteryWhitelist)
        val store = Store(RecoveryConsent(applied = granted, declined = declined))
        val permissions = RecoveryPermissions({ null }, store, "example.app")
        assertEquals(PermissionResult.NoRunner, permissions.revokeAll())
        assertEquals(granted, store.consent.value.applied)
        assertEquals(declined, store.consent.value.declined)
    }

    @Test fun sharedSecurePermissionFailureRetainsBothCapabilities() = runTest {
        val secure = setOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging)
        val store = Store(RecoveryConsent(applied = secure))
        var calls = 0
        val permissions = RecoveryPermissions({ ShellCommandRunner {
            calls++
            "${PermissionCommands.EXIT_MARK}1"
        } }, store, "example.app")
        assertTrue(permissions.revokeAll() is PermissionResult.Failed)
        assertEquals(secure, store.consent.value.applied)
        assertEquals(1, calls)
    }

    @Test fun stoppedManagerCannotRegrantPermissionsFromLateBinderCallback() = runTest {
        val store = Store(RecoveryConsent())
        var calls = 0
        val permissions = RecoveryPermissions(
            { ShellCommandRunner { calls++; "${PermissionCommands.EXIT_MARK}0" } },
            store, "example.app", allowApply = { false },
        )
        assertEquals(PermissionResult.NoRunner, permissions.applyPending())
        assertEquals(0, calls)
        assertTrue(store.consent.value.applied.isEmpty())
    }

    @Test fun disablingOneSecureFeatureKeepsPermissionNeededByTheOther() = runTest {
        val secure = setOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging)
        val store = Store(RecoveryConsent(applied = secure))
        var calls = 0
        val permissions = RecoveryPermissions({ ShellCommandRunner {
            calls++
            "${PermissionCommands.EXIT_MARK}0"
        } }, store, "example.app")
        assertEquals(PermissionResult.Done, permissions.set(RecoveryCapability.RestoreAfterReboot, false))
        assertEquals(0, calls)
        assertEquals(setOf(RecoveryCapability.AutoWirelessDebugging), store.consent.value.applied)
        assertEquals(PermissionResult.Done, permissions.set(RecoveryCapability.AutoWirelessDebugging, false))
        assertEquals(1, calls)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun queuedGrantRechecksStopAfterAcquiringPermissionLock() = runTest {
        var allowed = true
        var calls = 0
        val finishFirst = CompletableDeferred<Unit>()
        val store = Store(RecoveryConsent())
        val permissions = RecoveryPermissions({ ShellCommandRunner {
            calls++
            finishFirst.await()
            "${PermissionCommands.EXIT_MARK}0"
        } }, store, "example.app", allowApply = { allowed })
        val first = async { permissions.set(RecoveryCapability.BatteryWhitelist, true) }
        runCurrent()
        val queued = async { permissions.set(RecoveryCapability.RestoreAfterReboot, true) }
        runCurrent()
        allowed = false
        finishFirst.complete(Unit)
        assertEquals(PermissionResult.Done, first.await())
        assertEquals(PermissionResult.NoRunner, queued.await())
        assertEquals(1, calls)
        assertEquals(setOf(RecoveryCapability.BatteryWhitelist), store.consent.value.applied)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun stopDuringAccessibilityGapNeverReenablesAndKeepsCleanupRecord() = runTest {
        var allowed = true
        val commands = mutableListOf<String>()
        val granted = setOf(RecoveryCapability.Accessibility)
        val store = Store(RecoveryConsent(applied = granted))
        val permissions = RecoveryPermissions({ ShellCommandRunner { command ->
            commands += command
            if (command == PermissionCommands.READ_ACCESSIBILITY) {
                PermissionCommands.accessibilityService("example.app")
            } else "${PermissionCommands.EXIT_MARK}0"
        } }, store, "example.app", allowApply = { allowed })
        val job = launch { permissions.restartAccessibility(1_000L) }
        runCurrent()
        allowed = false
        job.cancel()
        advanceUntilIdle()
        assertEquals(3, commands.size)
        assertFalse(commands.any { "settings put secure enabled_accessibility_services" in it })
        assertEquals(granted, store.consent.value.applied)
    }

}
