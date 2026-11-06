package io.github.nanomuse.shell.recovery

import com.openminis.app.logging.AppLogger
import io.github.nanomuse.shell.setup.BuiltinShellStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class A11yObservation(val enabled: Boolean, val connected: Boolean) {
    val stuck: Boolean get() = !connected
}

enum class RepairPhase {
    Idle,
    Pending,
    Repairing,
}

data class RepairOutcome(val restarted: Boolean, val atMillis: Long, val attempts: Int)

/**
 * 无障碍服务断线自愈与健康监控：
 * 只要检测到服务断线（stuck），且满足自愈条件（持有 WRITE_SECURE_SETTINGS 权限或特权 Shell 处于运行状态），
 * 在 3 秒缓冲后，自动通过底层 Secure Settings 解绑后重新绑定，触发 Android 系统重新连接。
 */
class AccessibilityRepair(
    private val restart: suspend (gapMs: Long) -> PermissionResult,
    private val consent: () -> RecoveryConsent,
    private val direct: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val tag = "AccessibilityRepair"

    private val _last = MutableStateFlow<RepairOutcome?>(null)
    val last: StateFlow<RepairOutcome?> = _last.asStateFlow()

    private val _phase = MutableStateFlow(RepairPhase.Idle)
    val phase: StateFlow<RepairPhase> = _phase.asStateFlow()

    private enum class Result { Restored, GaveUp, Interrupted }

    suspend fun watch(
        service: StateFlow<A11yObservation>,
        helper: StateFlow<BuiltinShellStatus>,
        unlocked: StateFlow<Boolean> = MutableStateFlow(true),
    ) {
        var exhausted = false
        combine(service, helper, unlocked, ::Triple).conflate().collect { (s, h, u) ->
            if (s.connected) {
                exhausted = false
                return@collect
            }
            if (exhausted || !eligible(s, h, u)) return@collect
            try {
                _phase.value = RepairPhase.Pending
                AppLogger.info(tag, "accessibility disconnected, waiting ${GRACE_MS}ms grace period before repair...")
                delay(GRACE_MS)
                if (!eligible(service.value, helper.value, unlocked.value)) return@collect
                _phase.value = RepairPhase.Repairing
                AppLogger.info(tag, "triggering accessibility re-bind via secure settings...")
                exhausted = repair(service, helper, unlocked) == Result.GaveUp
            } finally {
                _phase.value = RepairPhase.Idle
            }
        }
    }

    private fun eligible(s: A11yObservation, h: BuiltinShellStatus, unlocked: Boolean) =
        s.stuck && unlocked && (direct() || h == BuiltinShellStatus.Running) && consent().wants(RecoveryCapability.Accessibility)

    private suspend fun repair(
        service: StateFlow<A11yObservation>,
        helper: StateFlow<BuiltinShellStatus>,
        unlocked: StateFlow<Boolean>,
    ): Result {
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                delay(BACKOFF_MS * (attempt - 1))
                val s = service.value
                if (s.connected) return Result.Restored.also { record(true, attempt - 1) }
                if (!eligible(s, helper.value, unlocked.value)) return Result.Interrupted
            }
            val res = restart(GAP_MS * attempt)
            if (res == PermissionResult.Done &&
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { service.first { it.connected } } != null
            ) {
                AppLogger.info(tag, "successfully restored accessibility service on attempt $attempt")
                return Result.Restored.also { record(true, attempt) }
            }
        }
        AppLogger.warning(tag, "gave up repairing accessibility after $MAX_ATTEMPTS attempts")
        record(false, MAX_ATTEMPTS)
        return Result.GaveUp
    }

    private fun record(restarted: Boolean, attempts: Int) {
        _last.value = RepairOutcome(restarted, now(), attempts)
    }

    companion object {
        const val GRACE_MS = 3_000L
        const val MAX_ATTEMPTS = 2
        const val GAP_MS = 1_000L
        const val BACKOFF_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val WORST_CASE_MS = GRACE_MS + MAX_ATTEMPTS * CONNECT_TIMEOUT_MS + GAP_MS * 3 + BACKOFF_MS + 2_000L

        suspend fun <T : Any> awaitService(
            service: StateFlow<T?>,
            phase: StateFlow<RepairPhase>,
            baseMs: Long,
            capMs: Long = WORST_CASE_MS,
        ): T? {
            withTimeoutOrNull(baseMs) { service.filterNotNull().first() }?.let { return it }
            if (phase.value == RepairPhase.Idle) return service.value
            return withTimeoutOrNull((capMs - baseMs).coerceAtLeast(0)) {
                combine(service, phase, ::Pair).first { (s, p) -> s != null || p == RepairPhase.Idle }.first
            }
        }
    }
}
