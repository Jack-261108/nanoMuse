package io.github.nanomuse.shell.recovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 替用户打开无线调试停下来的原因。 */
enum class AutoOnBlock {
    /** 写了打开, 系统马上又拨回关着: 这个 Wi-Fi 还没被允许无线调试。 */
    NotAllowedHere,
    /** 系统不让写(写入抛异常或返回失败)。 */
    Refused,
    /** 连着替用户打开了多次, 助手还是没起来。 */
    GaveUp,
}

sealed interface AutoOnGate {
    data object Act : AutoOnGate
    data class Wait(val ms: Long) : AutoOnGate
    data class Skip(val reason: AutoOnSkip) : AutoOnGate
}

enum class AutoOnSkip {
    Declined,
    NoPermission,
    NoWifi,
    Blocked,
}

/**
 * 内置助手要启动、无线调试却关着时, 替用户把它打开(只开不关)。
 * 带有 3 秒稳定检测(Settle Check): 若系统未信任该 Wi-Fi 而自动弹窗拨回 0, 立即熔断停写, 避免死循环弹窗。
 */
class WirelessDebuggingAutoOn(
    private val wants: () -> Boolean,
    private val canWrite: () -> Boolean,
    private val read: () -> Boolean?,
    private val write: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val settleMs: Long = SETTLE_MS,
    private val pollMs: Long = POLL_MS,
) {
    private val _blocked = MutableStateFlow<AutoOnBlock?>(null)
    val blocked: StateFlow<AutoOnBlock?> = _blocked.asStateFlow()

    @Volatile var writing = false
        private set

    private var writes = 0
    private var lastWriteAt = 0L

    @Synchronized
    fun gate(wifi: Boolean): AutoOnGate {
        if (_blocked.value == null && writes >= MAX_WRITES) _blocked.value = AutoOnBlock.GaveUp
        return decide(wants(), canWrite(), wifi, _blocked.value, writes, now() - lastWriteAt)
    }

    suspend fun turnOn(): Boolean {
        synchronized(this) {
            writing = true
            writes++
            lastWriteAt = now()
        }
        try {
            val accepted = runCatching(write).getOrDefault(false)
            if (!accepted || read() == false) {
                return false.also { block(if (accepted) AutoOnBlock.NotAllowedHere else AutoOnBlock.Refused) }
            }
            var waited = 0L
            while (waited < settleMs) {
                delay(pollMs)
                waited += pollMs
                if (read() == false) return false.also { block(AutoOnBlock.NotAllowedHere) }
            }
            return true
        } finally {
            writing = false
        }
    }

    @Synchronized
    fun reset() {
        writes = 0
        lastWriteAt = 0L
        _blocked.value = null
    }

    private fun block(reason: AutoOnBlock) {
        _blocked.value = reason
    }

    companion object {
        const val SETTLE_MS = 3_000L
        const val POLL_MS = 250L
        const val MAX_WRITES = 3
        const val BASE_GAP_MS = 10_000L
        const val MAX_GAP_MS = 5 * 60_000L

        fun gapAfter(writes: Int): Long =
            if (writes <= 0) 0L else (BASE_GAP_MS shl (writes - 1).coerceAtMost(20)).coerceAtMost(MAX_GAP_MS)

        fun decide(wants: Boolean, canWrite: Boolean, wifi: Boolean, blocked: AutoOnBlock?, writes: Int, sinceLastMs: Long): AutoOnGate {
            if (!wants) return AutoOnGate.Skip(AutoOnSkip.Declined)
            if (!canWrite) return AutoOnGate.Skip(AutoOnSkip.NoPermission)
            if (blocked != null || writes >= MAX_WRITES) return AutoOnGate.Skip(AutoOnSkip.Blocked)
            if (!wifi) return AutoOnGate.Skip(AutoOnSkip.NoWifi)
            val gap = gapAfter(writes)
            return if (sinceLastMs < gap) AutoOnGate.Wait(gap - sinceLastMs) else AutoOnGate.Act
        }
    }
}
