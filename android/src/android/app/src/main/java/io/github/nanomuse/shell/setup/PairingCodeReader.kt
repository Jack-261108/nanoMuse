package io.github.nanomuse.shell.setup

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import io.github.nanomuse.shell.SystemPickers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 配对时借无障碍服务读出系统配对窗口上的配对码, 读到就直接配对, 用户不用去通知里填。
 * 只在 [PairingCoordinator] 开着的这段时间里跑, 只看系统设置的窗口; 读到的码不写日志。
 * 无障碍没开就什么也不做, 配对照样走通知。
 */
internal object PairingCodeReader {
    private const val SETTLE_MS = 300L
    private const val MAX_NODES = 400

    suspend fun run(context: Context, control: BuiltinShellControl, coordinator: PairingCoordinator, sessionId: Long) {
        val settingsPkg = resolveSettingsPackage(context)
        val tried = mutableSetOf<String>()

        while (kotlin.coroutines.coroutineContext.isActive) {
            val service = MinisAccessibilityService.getInstance()
            if (service != null) {
                val portFound = control.pairingDiscovery.value is PairingDiscovery.Found
                val attempt = readings(service, settingsPkg).firstNotNullOfOrNull {
                    PairingScreen.attempt(
                        it,
                        portFound,
                        PairingScreen.effectiveStatus(control.status.value, coordinator.repairing.value),
                        tried,
                    )
                }
                if (attempt != null) {
                    tried += attempt.code
                    coordinator.pair(attempt.code, attempt.port, sessionId)
                }
            }
            delay(SETTLE_MS)
        }
    }

    private fun resolveSettingsPackage(context: Context): String {
        val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        return runCatching {
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        }.getOrNull() ?: "com.android.settings"
    }

    private fun readings(service: MinisAccessibilityService, settingsPkg: String): List<PairingScreenReading> {
        val windows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { service.windows }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        if (windows.isNotEmpty()) {
            return windows.mapNotNull { w ->
                val root = runCatching { w.root }.getOrNull() ?: return@mapNotNull null
                if (root.packageName?.toString() != settingsPkg) return@mapNotNull null
                PairingScreen.read(texts(root))
            }
        }

        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return emptyList()
        if (root.packageName?.toString() != settingsPkg) return emptyList()
        return listOf(PairingScreen.read(texts(root)))
    }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque(listOf(root))
        var seen = 0
        while (queue.isNotEmpty() && seen++ < MAX_NODES) {
            val n = queue.removeFirst()
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until n.childCount) {
                runCatching { n.getChild(i) }.getOrNull()?.let(queue::add)
            }
        }
        return out
    }
}
