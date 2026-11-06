package io.github.nanomuse.shell

import io.github.nanomuse.shell.recovery.A11yObservation
import io.github.nanomuse.shell.recovery.AccessibilityList
import io.github.nanomuse.shell.recovery.AccessibilityRepair
import io.github.nanomuse.shell.recovery.AutoOnBlock
import io.github.nanomuse.shell.recovery.AutoOnGate
import io.github.nanomuse.shell.recovery.AutoOnSkip
import io.github.nanomuse.shell.recovery.PermissionCommands
import io.github.nanomuse.shell.recovery.PermissionResult
import io.github.nanomuse.shell.recovery.RecoveryCapability
import io.github.nanomuse.shell.recovery.RecoveryConsent
import io.github.nanomuse.shell.recovery.RepairPhase
import io.github.nanomuse.shell.recovery.WirelessDebuggingAutoOn
import io.github.nanomuse.shell.setup.BuiltinShellStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecoveryTest {

    @Test
    fun wirelessDebuggingAutoOnDecisions() {
        // 用户关闭时不操作
        assertEquals(
            AutoOnGate.Skip(AutoOnSkip.Declined),
            WirelessDebuggingAutoOn.decide(wants = false, canWrite = true, wifi = true, blocked = null, writes = 0, sinceLastMs = 0),
        )

        // 无 WRITE_SECURE_SETTINGS 权限时不操作
        assertEquals(
            AutoOnGate.Skip(AutoOnSkip.NoPermission),
            WirelessDebuggingAutoOn.decide(wants = true, canWrite = false, wifi = true, blocked = null, writes = 0, sinceLastMs = 0),
        )

        // 没连 Wi-Fi 时不操作
        assertEquals(
            AutoOnGate.Skip(AutoOnSkip.NoWifi),
            WirelessDebuggingAutoOn.decide(wants = true, canWrite = true, wifi = false, blocked = null, writes = 0, sinceLastMs = 0),
        )

        // 熔断被阻止时不操作
        assertEquals(
            AutoOnGate.Skip(AutoOnSkip.Blocked),
            WirelessDebuggingAutoOn.decide(wants = true, canWrite = true, wifi = true, blocked = AutoOnBlock.NotAllowedHere, writes = 0, sinceLastMs = 0),
        )

        // 首次满足条件时立即执行
        assertEquals(
            AutoOnGate.Act,
            WirelessDebuggingAutoOn.decide(wants = true, canWrite = true, wifi = true, blocked = null, writes = 0, sinceLastMs = 0),
        )

        // 写入次数过多熔断
        assertEquals(
            AutoOnGate.Skip(AutoOnSkip.Blocked),
            WirelessDebuggingAutoOn.decide(wants = true, canWrite = true, wifi = true, blocked = null, writes = 3, sinceLastMs = 0),
        )
    }

    @Test
    fun accessibilityListManipulation() {
        val me = "io.github.nanomuse.app/com.openminis.app.accessibility.MinisAccessibilityService"
        val other = "com.other.app/.OtherService"

        // 解析列表
        val list = AccessibilityList.entries("$other:$me")
        assertEquals(listOf(other, me), list)

        // 检查包含
        assertTrue(AccessibilityList.contains("$other:$me", me))
        assertFalse(AccessibilityList.contains(other, me))

        // 添加自己
        val added = AccessibilityList.with(other, me)
        assertEquals("$other:$me", added)

        // 移除自己
        val removed = AccessibilityList.without("$other:$me", me)
        assertEquals(other, removed)

        // 格式化写入命令
        val cmds = PermissionCommands.writeAccessibility("$other:$me")
        assertEquals(2, cmds.size)
        assertTrue(cmds[0].contains("enabled_accessibility_services"))
        assertTrue(cmds[1].contains("accessibility_enabled 1"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun accessibilityRepairWatchRestoresDisconnectedService() = runTest {
        var restarts = 0
        val serviceFlow = MutableStateFlow(A11yObservation(enabled = true, connected = true))
        val helperFlow = MutableStateFlow<BuiltinShellStatus>(BuiltinShellStatus.Running)
        val unlockedFlow = MutableStateFlow(true)

        val repair = AccessibilityRepair(
            restart = {
                restarts++
                // 模拟系统重新连接成功
                serviceFlow.value = A11yObservation(enabled = true, connected = true)
                PermissionResult.Done
            },
            consent = { RecoveryConsent(applied = setOf(RecoveryCapability.Accessibility)) },
            direct = { true },
        )

        val job = launch {
            repair.watch(serviceFlow, helperFlow, unlockedFlow)
        }

        assertEquals(RepairPhase.Idle, repair.phase.value)
        assertEquals(0, restarts)

        // 模拟无障碍服务掉线
        serviceFlow.value = A11yObservation(enabled = true, connected = false)

        // 推进时间越过 3 秒缓冲
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 100)

        // 验证自动触发了重启并恢复了连接
        assertEquals(1, restarts)
        assertTrue(serviceFlow.value.connected)
        assertEquals(RepairPhase.Idle, repair.phase.value)

        job.cancel()
    }
}
